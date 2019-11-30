/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.codehaus.groovy.classgen;

import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.DynamicVariable;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.InnerClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.VariableScope;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.FieldExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.Types;

import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;

import static java.lang.reflect.Modifier.isFinal;
import static org.apache.groovy.ast.tools.MethodNodeUtils.getPropertyName;

/**
 * Initializes the variable scopes for an AST.
 */
public class VariableScopeVisitor extends ClassCodeVisitorSupport {

    private ClassNode currentClass;
    private VariableScope currentScope;
    private boolean inConstructor, isSpecialConstructorCall;

    private final SourceUnit source;
    private final boolean recurseInnerClasses;
    private final Deque<StateStackElement> stateStack = new LinkedList<>();

    private static class StateStackElement {
        final ClassNode clazz;
        final VariableScope scope;
        final boolean inConstructor;

        StateStackElement(final ClassNode currentClass, final VariableScope currentScope, final boolean inConstructor) {
            clazz = currentClass;
            scope = currentScope;
            this.inConstructor = inConstructor;
        }
    }

    public VariableScopeVisitor(SourceUnit source, boolean recurseInnerClasses) {
        this.source = source;
        this.currentScope = new VariableScope();
        this.recurseInnerClasses = recurseInnerClasses;
    }

    public VariableScopeVisitor(SourceUnit source) {
        this(source, false);
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return source;
    }

    // ------------------------------
    // helper methods
    //------------------------------

    private void pushState(final boolean isStatic) {
        stateStack.push(new StateStackElement(currentClass, currentScope, inConstructor));
        currentScope = new VariableScope(currentScope);
        currentScope.setInStaticContext(isStatic);
    }

    private void pushState() {
        pushState(currentScope.isInStaticContext());
    }

    private void popState() {
        StateStackElement state = stateStack.pop();
        currentClass = state.clazz;
        currentScope = state.scope;
        inConstructor = state.inConstructor;
    }

    private void declare(final VariableExpression variable) {
        variable.setInStaticContext(currentScope.isInStaticContext());
        declare(variable, variable);
        variable.setAccessedVariable(variable);
    }

    private void declare(final Variable variable, final ASTNode expression) {
        String scopeType = "scope";
        String variableType = "variable";

        if (expression.getClass() == FieldNode.class) {
            scopeType = "class";
            variableType = "field";
        } else if (expression.getClass() == PropertyNode.class) {
            scopeType = "class";
            variableType = "property";
        }

        StringBuilder msg = new StringBuilder();
        msg.append("The current ").append(scopeType);
        msg.append(" already contains a ").append(variableType);
        msg.append(" of the name ").append(variable.getName());

        if (currentScope.getDeclaredVariable(variable.getName()) != null) {
            addError(msg.toString(), expression);
            return;
        }

        for (VariableScope scope = currentScope.getParent(); scope != null; scope = scope.getParent()) {
            // if we are in a class and no variable is declared until
            // now, then we can break the loop, because we are allowed
            // to declare a variable of the same name as a class member
            if (scope.getClassScope() != null && !isAnonymous(scope.getClassScope())) break;

            if (scope.getDeclaredVariable(variable.getName()) != null) {
                // variable already declared
                addError(msg.toString(), expression);
                break;
            }
        }
        // declare the variable even if there was an error to allow more checks
        currentScope.putDeclaredVariable(variable);
    }

    private Variable findClassMember(final ClassNode cn, final String name) {
        if (cn == null) return null;

        if (cn.isScript()) {
            return new DynamicVariable(name, false);
        }

        for (FieldNode fn : cn.getFields()) {
            if (name.equals(fn.getName())) return fn;
        }

        for (MethodNode mn : cn.getMethods()) {
            if (name.equals(getPropertyName(mn))) {
                PropertyNode property = new PropertyNode(name, mn.getModifiers(), ClassHelper.OBJECT_TYPE, cn, null, null, null);
                property.getField().setHasNoRealSourcePosition(true);
                property.getField().setSynthetic(true);
                property.getField().setDeclaringClass(cn);
                property.setDeclaringClass(cn);
                return property;
            }
        }

        for (PropertyNode pn : cn.getProperties()) {
            if (pn.getName().equals(name)) return pn;
        }

        Variable ret = findClassMember(cn.getSuperClass(), name);
        if (ret != null) return ret;
        if (isAnonymous(cn)) return null;
        return findClassMember(cn.getOuterClass(), name);
    }

    private static boolean isAnonymous(final ClassNode node) {
        return (!node.isEnum() && node instanceof InnerClassNode && ((InnerClassNode) node).isAnonymous());
    }

    private void markClosureSharedVariables() {
        for (Iterator<Variable> it = currentScope.getReferencedLocalVariablesIterator(); it.hasNext(); ) {
            Variable variable = it.next();
            variable.setClosureSharedVariable(true);
        }
    }

    // -------------------------------
    // different Variable-based checks
    // -------------------------------

    private void checkFinalFieldAccess(final Expression expression) {
        if (expression instanceof TupleExpression) {
            TupleExpression tuple = (TupleExpression) expression;
            for (Expression tupleExpression : tuple.getExpressions()) {
                checkForFinal(expression, (VariableExpression) tupleExpression);
            }
        } else if (expression instanceof VariableExpression) {
            checkForFinal(expression, (VariableExpression) expression);
        }
        // currently not looking for PropertyExpression: dealt with at runtime using ReadOnlyPropertyException
    }

    private void checkForFinal(final Expression context, final VariableExpression expression) {
        Variable variable = expression.getAccessedVariable();
        if (variable != null) {
            if (isFinal(variable.getModifiers()) && variable instanceof Parameter) {
                addError("Cannot assign a value to final variable '" + variable.getName() + "'", context);
            }
            // TODO: handle local variables
        }
    }

    private Variable checkVariableNameForDeclaration(final String name, final Expression expression) {
        if ("super".equals(name) || "this".equals(name)) return null;

        VariableScope scope = currentScope;
        Variable var = new DynamicVariable(name, currentScope.isInStaticContext());
        Variable orig = var;
        // try to find a declaration of a variable
        boolean crossingStaticContext = false;
        while (true) {
            crossingStaticContext = (crossingStaticContext || scope.isInStaticContext());

            Variable var1 = scope.getDeclaredVariable(var.getName());
            if (var1 != null) {
                var = var1;
                break;
            }

            var1 = scope.getReferencedLocalVariable(var.getName());
            if (var1 != null) {
                var = var1;
                break;
            }

            var1 = scope.getReferencedClassVariable(var.getName());
            if (var1 != null) {
                var = var1;
                break;
            }

            ClassNode classScope = scope.getClassScope();
            if (classScope != null) {
                Variable member = findClassMember(classScope, var.getName());
                if (member != null) {
                    boolean staticScope = (crossingStaticContext || isSpecialConstructorCall), staticMember = member.isInStaticContext();
                    // prevent a static context (e.g. a static method) from accessing a non-static variable (e.g. a non-static field)
                    if (!(staticScope && !staticMember)) {
                        var = member;
                    }
                }
                // GROOVY-5961
                if (!isAnonymous(classScope)) break;
            }
            scope = scope.getParent();
        }
        if (var == orig && crossingStaticContext) {
            var = new DynamicVariable(var.getName(), true);
        }

        VariableScope end = scope;
        scope = currentScope;
        while (scope != end) {
            if (end.isClassScope() || (end.isReferencedClassVariable(name) && end.getDeclaredVariable(name) == null)) {
                scope.putReferencedClassVariable(var);
            } else {
                scope.putReferencedLocalVariable(var);
            }
            scope = scope.getParent();
        }

        return var;
    }

    /**
     * A property on "this", like this.x is transformed to a direct field access,
     * so we need to check the static context here.
     */
    private void checkPropertyOnExplicitThis(final PropertyExpression expression) {
        if (!currentScope.isInStaticContext()) return;
        Expression object = expression.getObjectExpression();
        if (!(object instanceof VariableExpression)) return;
        VariableExpression ve = (VariableExpression) object;
        if (!ve.getName().equals("this")) return;
        String name = expression.getPropertyAsString();
        if (name == null || name.equals("class")) return;
        Variable member = findClassMember(currentClass, name);
        if (member == null) return;
        checkVariableContextAccess(member, expression);
    }

    private void checkVariableContextAccess(final Variable variable, final Expression expression) {
        if (variable.isInStaticContext() || !currentScope.isInStaticContext()) return;

        addError(variable.getName() + " is declared in a dynamic context, but you tried to access it from a static context.", expression);

        // declare a static variable to be able to continue the check
        currentScope.putDeclaredVariable(new DynamicVariable(variable.getName(), currentScope.isInStaticContext()));
    }

    //--------------------------------------------------------------------------

    @Override
    public void visitClass(final ClassNode node) {
        // AIC are already done, doing them here again will lead to wrong scopes
        if (isAnonymous(node)) return;

        pushState();
        currentClass = node;
        currentScope.setClassScope(node);

        super.visitClass(node);
        if (recurseInnerClasses) {
            for (Iterator<InnerClassNode> innerClasses = node.getInnerClasses(); innerClasses.hasNext(); ) {
                visitClass(innerClasses.next());
            }
        }
        popState();
    }

    @Override
    public void visitField(final FieldNode node) {
        pushState(node.isStatic());
        super.visitField(node);
        popState();
    }

    @Override
    public void visitProperty(final PropertyNode node) {
        pushState(node.isStatic());
        super.visitProperty(node);
        popState();
    }

    @Override
    protected void visitConstructorOrMethod(final MethodNode node, final boolean isConstructor) {
        pushState(node.isStatic());
        inConstructor = isConstructor;
        node.setVariableScope(currentScope);

        visitAnnotations(node);
        for (Parameter parameter : node.getParameters()) {
            visitAnnotations(parameter);
        }
        // add parameters to scope and visit init expressions after annotations
        // to prevent the use of parameters in annotation attributes
        for (Parameter parameter : node.getParameters()) {
            if (parameter.hasInitialExpression()) {
                parameter.getInitialExpression().visit(this);
            }
            declare(parameter, node);
        }
        visitClassCodeContainer(node.getCode());

        popState();
    }

    // statements:

    @Override
    public void visitBlockStatement(final BlockStatement statement) {
        pushState();
        statement.setVariableScope(currentScope);
        super.visitBlockStatement(statement);
        popState();
    }

    @Override
    public void visitCatchStatement(final CatchStatement statement) {
        pushState();
        Parameter parameter = statement.getVariable();
        parameter.setInStaticContext(currentScope.isInStaticContext());
        declare(parameter, statement);
        super.visitCatchStatement(statement);
        popState();
    }

    @Override
    public void visitForLoop(final ForStatement statement) {
        pushState();
        statement.setVariableScope(currentScope);
        Parameter parameter = statement.getVariable();
        parameter.setInStaticContext(currentScope.isInStaticContext());
        if (parameter != ForStatement.FOR_LOOP_DUMMY) declare(parameter, statement);
        super.visitForLoop(statement);
        popState();
    }

    @Override
    public void visitIfElse(final IfStatement statement) {
        statement.getBooleanExpression().visit(this);
        pushState();
        statement.getIfBlock().visit(this);
        popState();
        pushState();
        statement.getElseBlock().visit(this);
        popState();
    }

    // expressions:

    @Override
    public void visitBinaryExpression(final BinaryExpression expression) {
        super.visitBinaryExpression(expression);

        if (Types.isAssignment(expression.getOperation().getType())) {
            checkFinalFieldAccess(expression.getLeftExpression());
        }
    }

    @Override
    public void visitClosureExpression(final ClosureExpression expression) {
        pushState();

        expression.setVariableScope(currentScope);

        if (expression.isParameterSpecified()) {
            for (Parameter parameter : expression.getParameters()) {
                parameter.setInStaticContext(currentScope.isInStaticContext());
                if (parameter.hasInitialExpression()) {
                    parameter.getInitialExpression().visit(this);
                }
                declare(parameter, expression);
            }
        } else if (expression.getParameters() != null) {
            Parameter var = new Parameter(ClassHelper.OBJECT_TYPE, "it");
            var.setInStaticContext(currentScope.isInStaticContext());
            currentScope.putDeclaredVariable(var);
        }

        super.visitClosureExpression(expression);
        markClosureSharedVariables();

        popState();
    }

    @Override
    public void visitConstructorCallExpression(final ConstructorCallExpression expression) {
        isSpecialConstructorCall = expression.isSpecialCall();
        super.visitConstructorCallExpression(expression);
        isSpecialConstructorCall = false;
        if (!expression.isUsingAnonymousInnerClass()) return;

        pushState();
        InnerClassNode innerClass = (InnerClassNode) expression.getType();
        innerClass.setVariableScope(currentScope);
        currentScope.setClassScope(innerClass);
        currentScope.setInStaticContext(false);
        for (MethodNode method : innerClass.getMethods()) {
            Parameter[] parameters = method.getParameters();
            if (parameters.length == 0) {
                parameters = null; // null means no implicit "it"
            }
            visitClosureExpression(new ClosureExpression(parameters, method.getCode()));
        }

        for (FieldNode field : innerClass.getFields()) {
            Expression initExpression = field.getInitialExpression();
            pushState(field.isStatic());
            if (initExpression != null) {
                if (initExpression.isSynthetic() && initExpression instanceof VariableExpression
                        && ((VariableExpression) initExpression).getAccessedVariable() instanceof Parameter) {
                    // GROOVY-6834: accessing a parameter which is not yet seen in scope
                    popState();
                    continue;
                }
                initExpression.visit(this);
            }
            popState();
        }

        for (Statement initStatement : innerClass.getObjectInitializerStatements()) {
            initStatement.visit(this);
        }
        markClosureSharedVariables();
        popState();
    }

    @Override
    public void visitDeclarationExpression(final DeclarationExpression expression) {
        visitAnnotations(expression);
        // visit right side first to prevent the use of a variable before its declaration
        expression.getRightExpression().visit(this);

        if (expression.isMultipleAssignmentDeclaration()) {
            TupleExpression list = expression.getTupleExpression();
            for (Expression listExpression : list.getExpressions()) {
                declare((VariableExpression) listExpression);
            }
        } else {
            declare(expression.getVariableExpression());
        }
    }

    @Override
    public void visitFieldExpression(final FieldExpression expression) {
        String name = expression.getFieldName();
        //TODO: change that to get the correct scope
        Variable variable = checkVariableNameForDeclaration(name, expression);
        checkVariableContextAccess(variable, expression);
    }

    @Override
    public void visitMethodCallExpression(final MethodCallExpression expression) {
        if (expression.isImplicitThis() && expression.getMethod() instanceof ConstantExpression) {
            ConstantExpression methodNameConstant = (ConstantExpression) expression.getMethod();
            String methodName = methodNameConstant.getText();

            if (methodName == null) {
                throw new GroovyBugError("method name is null");
            }

            Variable variable = checkVariableNameForDeclaration(methodName, expression);
            if (variable != null && !(variable instanceof DynamicVariable)) {
                checkVariableContextAccess(variable, expression);
            }

            if (variable instanceof VariableExpression || variable instanceof Parameter) {
                VariableExpression object = new VariableExpression(variable);
                object.setSourcePosition(methodNameConstant);
                expression.setObjectExpression(object);
                ConstantExpression method = new ConstantExpression("call");
                method.setSourcePosition(methodNameConstant); // important for GROOVY-4344
                expression.setImplicitThis(false);
                expression.setMethod(method);
            }
        }
        super.visitMethodCallExpression(expression);
    }

    @Override
    public void visitPropertyExpression(final PropertyExpression expression) {
        expression.getObjectExpression().visit(this);
        expression.getProperty().visit(this);
        checkPropertyOnExplicitThis(expression);
    }

    @Override
    public void visitVariableExpression(final VariableExpression expression) {
        String name = expression.getName();
        Variable variable = checkVariableNameForDeclaration(name, expression);
        if (variable == null) return;
        expression.setAccessedVariable(variable);
        checkVariableContextAccess(variable, expression);
    }
}
