from tree_sitter import Language, Parser, Node
import tree_sitter_java as tsjava
import pprint
from typing import List

class BlockTreeNode:
    def __init__(self, name, node, start, end):
        self.children = []
        self.start = start
        self.end = end
        self.acquire = False
        self.release = False
        self.node = node
        self.name = name

    def __str__(self):
        output = self.name+" "+str(self.start)+" "+str(self.end)
        if self.children != []:
            output += "\n\t"
        print(output)
        for n in self.children:
            if n is not None:
                print(n)
                output += n.__str__()


def insertCondBlocks(nodes: list[BlockTreeNode], block: Node) -> bool:
    for n in nodes:
        if n.start <= block.start_byte <= n.end:
            if(insertCondBlocks(n.children, block)):
                pass
            else:
                n.children.append(BlockTreeNode("if", block, block.start_byte, block.end_byte))
            return True
    return False

def insertSemCall(nodes: list[BlockTreeNode], node: Node) -> bool:
    for n in nodes:
        if n.start <= node.start_byte <= n.end:
            if n.children != [] and insertSemCall(n.children, node):
                return True
            else:
                if node.text.decode('utf-8') == "acquire":
                    n.acquire = True
                else:
                    n.release = True
                return True
    return False

def checkViolation(nodes: list[BlockTreeNode], acquire: bool, release: bool):
    for n in nodes:
        lock = acquire or n.acquire
        unlock = release or n.release
        if len(n.children) == 0 and lock and not unlock:
            print(f"Error: Acquire without release detected, name: {n.name}, byte {n.start}")
        elif len(n.children) > 0:
            checkViolation(n.children, lock, unlock)

            
example = """
import java.util.concurrent.Semaphore;
public class MutexDemo {
    // create a Semaphore instance that makes it so only 1 thread can access resource at a time
    private static Semaphore mutex = new Semaphore(1);

    public static void main(String[] args) {
        mutex.acquire();
        if (true) {
            mutex.release();
        }
        else {
            System.out.Println("foo");
        }
    }
}
"""

JAVA_LANGUAGE = Language(tsjava.language())

parser = Parser(JAVA_LANGUAGE)

tree = parser.parse(example.encode())
node = tree.root_node

doc_str_pattern = """
(field_declaration
	(type_identifier) @type
    (variable_declarator
    	(identifier) @ident))
(method_declaration
	(identifier) @method
    (block) @body)
(if_statement
	(block) @block)
(method_invocation
	object: (identifier) @object
    name: (identifier) @name)
"""
query = JAVA_LANGUAGE.query(doc_str_pattern)
caps = query.matches(node)

methods: List[BlockTreeNode] = []
sem_name = ""


for i in [i[1] for i in caps if i[0] == 0]:
    if i["type"].text == b"Semaphore":
        sem_name = i["ident"].text.decode('utf-8')

print(f"Semaphore: {sem_name}")

for i in [i[1] for i in caps if i[0] == 1]:
    methods.append(BlockTreeNode(i["method"].text.decode('utf-8'), i["method"], i["body"].start_byte, i["body"].end_byte))

for i in [i[1] for i in caps if i[0] == 2]:
    insertCondBlocks(methods, i["block"])

for i in [i[1] for i in caps if i[0] == 3]:
    if i["object"].text.decode('utf-8') == sem_name:
        insertSemCall(methods, i["name"])

checkViolation(methods, False, False)
