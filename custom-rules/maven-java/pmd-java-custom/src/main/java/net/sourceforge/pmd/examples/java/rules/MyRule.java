package net.sourceforge.pmd.examples.java.rules;

import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.java.ast.*;
import org.checkerframework.checker.nullness.qual.NonNull;
import net.sourceforge.pmd.lang.java.types.TypeTestUtil;
import net.sourceforge.pmd.lang.java.rule.AbstractJavaRule;
import net.sourceforge.pmd.lang.rule.RuleTargetSelector;
import net.sourceforge.pmd.properties.PropertyDescriptor;
import net.sourceforge.pmd.properties.PropertyFactory;

import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class MyRule extends AbstractJavaRule {
    Map<String, Boolean> classLockNames = new HashMap<>(); //keep track of lock variable name : locked

    @Override
    public Object visit(final ASTClassType node, final Object data) {
        // find if field declaration by checking parent
        //todo: do we have to check further ancestors?
        Node parent = node.getParent();
        if (parent instanceof ASTFieldDeclaration) {
            ASTFieldDeclaration fieldDecl = (ASTFieldDeclaration) parent;
//            if (TypeTestUtil.isA(Lock.class, node)) {//todo: why doesn't this check work?
            if (TypeTestUtil.isA("Lock", node)) {
                // get ASTVariableDeclarator child
                for (Node child : fieldDecl) {
                    if (child instanceof ASTVariableId) {
                        String lockName = ((ASTVariableId) child).getName();
                        classLockNames.put(lockName, true);
                    }
                }
            }
        }

        return super.visit(node, data);
    }

    @Override
    public Object visit(final ASTMethodCall node, final Object data) {
        // objects should be allocated already at this point
        if (node.getMethodName().equals("lock")) {
            System.out.println(classLockNames);
            // need to get calling object
            ASTVariableAccess variableAccess = (ASTVariableAccess) node.getFirstChild(); //should be ASTVariableAccess
            String variableName = variableAccess.getName();
            if (classLockNames.containsKey(variableName)) {
                classLockNames.put(variableName, true);
                // need to look through the rest of the code block to see if the lock is released
                // todo: do we need to do a full traversal back up to check for the enclosing block? or will it always be the parent?
                boolean finallyClauseFound = false;
                boolean unlocked = false;
                ASTBlock enclosingBlock = variableAccess.ancestors(ASTBlock.class).first();
                if (enclosingBlock != null) {
                    // check the same variable unlocks in block
                    // look for finally block
                    ASTFinallyClause finallyClause = enclosingBlock.descendants(ASTFinallyClause.class).first();
                    if (finallyClause != null) {
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
