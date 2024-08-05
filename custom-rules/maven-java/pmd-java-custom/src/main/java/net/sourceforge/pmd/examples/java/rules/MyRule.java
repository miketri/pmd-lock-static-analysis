package net.sourceforge.pmd.examples.java.rules;

import net.sourceforge.pmd.lang.java.ast.*;
import net.sourceforge.pmd.lang.java.types.TypeTestUtil;
import net.sourceforge.pmd.lang.java.rule.AbstractJavaRule;

import java.util.*;
import java.util.concurrent.locks.Lock;

public class MyRule extends AbstractJavaRule {
    Set<String> classLocks = new HashSet<>(); // Track lock variable assignments
    Stack<Set<String>> localLocks = new Stack<>();


    private void addLockToCurrentContext(String lockVariableName) {
        localLocks.peek().add(lockVariableName);
    }

    private boolean lockInCurrentContext(String lockVariableName) {
        return localLocks.peek().contains(lockVariableName);
    }

    @Override
    public Object visit(ASTBlock node, Object data) {
        localLocks.push(new HashSet<>());
        Object result = super.visit(node, data);
        localLocks.pop();
        return result;
    }

    @Override
    public Object visit(final ASTFieldDeclaration node, final Object data) {
        if (TypeTestUtil.isA(Lock.class, node.getTypeNode())) {
            // get ASTVariableDeclarator child
            ASTVariableDeclarator child = node.firstChild(ASTVariableDeclarator.class);
            if (child != null) {
                String lockName = (child).getName();
                classLocks.add(lockName);
            }
        }

        return super.visit(node, data);
    }

    @Override
    public Object visit(final ASTLocalVariableDeclaration node, final Object data) {
        // is there a better way to keep track of variable assignments?
        if (TypeTestUtil.isA(Lock.class, node.getTypeNode())) {
            System.out.println("local lock");
            ASTVariableDeclarator variableDeclarator = node.descendants(ASTVariableDeclarator.class).first();
            if (variableDeclarator != null) {
                String variableName = variableDeclarator.getName();
                this.addLockToCurrentContext(variableName);
            }
        }
        return super.visit(node, data);
    }

    @Override
    public Object visit(final ASTMethodCall node, final Object data) {
        // objects should be allocated already at this point
        if (node.getMethodName().equals("lock")) {
            // need to get calling object
            ASTVariableAccess variableAccess = (ASTVariableAccess) node.getFirstChild(); //should be ASTVariableAccess
            String variableName = variableAccess.getName();
            if (classLocks.contains(variableName) || this.lockInCurrentContext(variableName)) {
                // need to look through the rest of the code block to see if the lock is released
                // todo: do we need to do a full traversal back up to check for the enclosing block? or will it always be the parent?
                boolean finallyClauseFound = false;
                boolean unlocked = false;
                ASTBlock enclosingBlock = variableAccess.ancestors(ASTBlock.class).first();
                if (enclosingBlock != null) {
                    // check the same variable unlocks in block
                    // go over all the try statements, one of them should have FinallyClause
                    List<ASTFinallyClause> finallyClauses = enclosingBlock.children(ASTTryStatement.class).children(ASTFinallyClause.class).toList();
                    for (ASTFinallyClause finallyClause : finallyClauses) {
                        finallyClauseFound = true;
                        // check if unlock in finally
                        unlocked = finallyClause.descendants(ASTMethodCall.class)
                                .toStream()
                                .anyMatch(astMethodCall -> astMethodCall.getMethodName().equals("unlock") && ((ASTVariableAccess) astMethodCall.getFirstChild()).getName().equals(variableName));
                    }

                    if (!finallyClauseFound) {
                        asCtx(data).addViolation(node, "Lock.lock() should have finally block in the same block.");
                    }

                    if (!unlocked) {
                        asCtx(data).addViolation(node, "Lock.lock() should have unlock() called.");
                    }

                }
            }
        }

        return super.visit(node, data);
    }

}
