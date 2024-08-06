package net.sourceforge.pmd.examples.java.rules;

import net.sourceforge.pmd.lang.ast.NodeStream;
import net.sourceforge.pmd.lang.java.ast.*;
import net.sourceforge.pmd.lang.java.types.TypeTestUtil;
import net.sourceforge.pmd.lang.java.rule.AbstractJavaRule;

import java.util.*;
import java.util.concurrent.locks.Lock;

public class MyRule extends AbstractJavaRule {
    static final String LOCK_METHOD_NAME = "lock";
    static final String UNLOCK_METHOD_NAME = "unlock";
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
        // keep track of the scope of where we are when we enter a code block to track local variables
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
//            System.out.println("local lock");
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
        if (LOCK_METHOD_NAME.equals(node.getMethodName())) {
            // need to get calling object
            ASTVariableAccess variableAccess = (ASTVariableAccess) node.getFirstChild(); //should be ASTVariableAccess
            String variableName = variableAccess.getName();
            if (classLocks.contains(variableName) || this.lockInCurrentContext(variableName)) {
                // need to look through the rest of the code block to see if the lock is released
                boolean finallyClauseFound;
                boolean unlocked;
                ASTBlock enclosingBlock = variableAccess.ancestors(ASTBlock.class).first();
                if (enclosingBlock != null) {
                    // check the same variable unlocks in block
                    // go over all the try statements, one of them should have FinallyClause
                    NodeStream<ASTFinallyClause> finallyClauses = enclosingBlock.children(ASTTryStatement.class).children(ASTFinallyClause.class);
                    finallyClauseFound = !finallyClauses.isEmpty();

                    if (!finallyClauseFound) {
                        asCtx(data).addViolation(node, "Lock.lock() should have finally block in the same block.");

                        // did user unlock outside of a finally?
                        unlocked = enclosingBlock.descendants(ASTMethodCall.class).any(astMethodCall ->
                                UNLOCK_METHOD_NAME.equals(astMethodCall.getMethodName()) &&
                                        ((ASTVariableAccess) astMethodCall.getFirstChild()).getName().equals(variableName)
                        );

                        if (unlocked) {
                            asCtx(data).addViolation(node, "Lock.unlock() should be called in a finally block.");
                        } else {
                            asCtx(data).addViolation(node, "Lock.lock() should have unlock() called.");
                        }
                    } else {
                        //there was a finally block
                        unlocked = finallyClauses
                                .any(finallyClause ->
                                        finallyClause.descendants(ASTMethodCall.class)
                                                .any(astMethodCall ->
                                                        UNLOCK_METHOD_NAME.equals(astMethodCall.getMethodName()) &&
                                                                ((ASTVariableAccess) astMethodCall.getFirstChild()).getName().equals(variableName) //make sure the same variable is calling unlock and lock
                                                )
                                );
                        if (!unlocked) {
                            asCtx(data).addViolation(node, "Lock.unlock() should be called in a finally block.");
                        }
                    }

                }
            }
        }

        return super.visit(node, data);
    }

}
