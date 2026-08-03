package lox;

import java.io.*;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.locks.ReentrantLock;

public class Interpreter implements Expr.Visitor<Object>, Stmt.Visitor<Void> {

    final Environment globals;
    private Environment environment;
    private final Map<Expr, Integer> locals;

    Interpreter(){
        this.globals = new Environment();
        this.environment = globals;
        this.locals = new HashMap<>();
        defineNative();
    }

    Interpreter(Interpreter original){
        this.globals = original.globals;
        this.locals = original.locals;
        this.environment = original.environment;
    }

    void interpret(List<Stmt> statements) {
        try {
            for (Stmt statement : statements) {
                execute(statement);
            }
        } catch (RuntimeError error) {
            Lox.runtimeError(error);
        }
    }

    private Object evaluate(Expr expr) {
        return expr.accept(this);
    }

    private void execute(Stmt stmt) {
        stmt.accept(this);
    }

    void resolve(Expr expr, int depth){
        locals.put(expr, depth);
    }

    void executeBlock(List<Stmt> statements, Environment environment) {
        Environment previous = this.environment;
        try {
            this.environment = environment;

            for (Stmt statement : statements) {
                execute(statement);
            }
        } finally {
            this.environment = previous;
        }
    }

    @Override
    public Void visitBlockStmt(Stmt.Block stmt) {
        executeBlock(stmt.statements, new Environment(environment));
        return null;
    }

    @Override
    public Void visitClassStmt(Stmt.Class stmt) {
        Object superclass = null;
        if(stmt.superclass != null){
            superclass = evaluate(stmt.superclass);
            if(!(superclass instanceof LoxClass)){
                throw new RuntimeError(stmt.superclass.name, "Superclass must be a class.");
            }
        }

        environment.define(stmt.name.lexeme, null);

        if(stmt.superclass != null){
            environment = new Environment(environment);
            environment.define("super", superclass);
        }

        Map<String, LoxFunction> methods = new HashMap<>();
        for(Stmt.Function method : stmt.methods){
            LoxFunction function = new LoxFunction(method, environment, method.name.lexeme.equals("init"));
            methods.put(method.name.lexeme, function);
        }

        LoxClass klass = new LoxClass(stmt.name.lexeme, (LoxClass)superclass, methods);

        if(superclass != null){
            environment = environment.enclosing;
        }

        environment.assign(stmt.name, klass);
        return null;
    }

    @Override
    public Void visitExpressionStmt(Stmt.Expression stmt) {
        evaluate(stmt.expression);
        return null;
    }

    @Override
    public Void visitFunctionStmt(Stmt.Function stmt) {
        LoxFunction function = new LoxFunction(stmt, environment, false);
        environment.define(stmt.name.lexeme, function);
        return null;
    }

    public Void visitIfStmt(Stmt.If stmt) {
        if (isTruthy(evaluate(stmt.condition))) {
            execute(stmt.thenBranch);
        } else if (stmt.elseBranch != null) {
            execute(stmt.elseBranch);
        }
        return null;
    }

    @Override
    public Void visitPrintStmt(Stmt.Print stmt) {
        Object value = evaluate(stmt.expression);
        System.out.println(stringify(value));
        return null;
    }

    @Override
    public Void visitReturnStmt(Stmt.Return stmt) {
        Object value = null;
        if(stmt.value != null) value = evaluate(stmt.value);

        throw new Return(value);
    }

    @Override
    public Void visitVarStmt(Stmt.Var stmt) {
        Object value = null;
        if (stmt.initializer != null) {
            value = evaluate(stmt.initializer);
        }

        environment.define(stmt.name.lexeme, value);
        return null;
    }

    @Override
    public Void visitWhileStmt(Stmt.While stmt) {
        while (isTruthy(evaluate(stmt.condition))) {
            execute(stmt.body);
        }
        return null;
    }

    @Override
    public Object visitAssignExpr(Expr.Assign expr) {
        Object value = evaluate(expr.value);

        Integer distance = locals.get(expr);
        if(distance != null){
            environment.assignAt(distance, expr.name, value);
        } else{
            globals.assign(expr.name, value);
        }

        return value;
    }

    @Override
    public Object visitBinaryExpr(Expr.Binary expr) {
        Object left = evaluate(expr.left);
        Object right = evaluate(expr.right);

        switch (expr.operator.type) {
            case GREATER:
                checkNumberOperands(expr.operator, left, right);
                return (double) left > (double) right;
            case GREATER_EQUAL:
                checkNumberOperands(expr.operator, left, right);
                return (double) left >= (double) right;
            case LESS:
                checkNumberOperands(expr.operator, left, right);
                return (double) left < (double) right;
            case LESS_EQUAL:
                checkNumberOperands(expr.operator, left, right);
                return (double) left <= (double) right;
            case BANG_EQUAL:
                return !isEqual(left, right);
            case EQUAL_EQUAL:
                return isEqual(left, right);
            case MINUS:
                checkNumberOperands(expr.operator, left, right);
                return (double) left - (double) right;
            case PLUS:
                if (left instanceof Double && right instanceof Double) {
                    return (double) left + (double) right;
                }

                if (left instanceof String && right instanceof String) {
                    return (String) left + (String) right;
                }

                throw new RuntimeError(expr.operator, "Operands must be two numbers or two strings");
            case SLASH:
                checkNumberOperands(expr.operator, left, right);
                return (double) left / (double) right;
            case STAR:
                checkNumberOperands(expr.operator, left, right);
                return (double) left * (double) right;
        }

        //Unreachable
        return null;
    }

    @Override
    public Object visitCallExpr(Expr.Call expr) {
        Object callee = evaluate(expr.callee);
        List<Object> arguments = new ArrayList<>();
        for(Expr argument : expr.arguments){
            arguments.add(evaluate(argument));
        }

        if(!(callee instanceof LoxCallable)){
            throw new RuntimeError(expr.paren, "Can only call functions and classes");
        }

        LoxCallable function = (LoxCallable)callee;
        if(function.arity() != -1 && arguments.size() != function.arity()){
            throw new RuntimeError(expr.paren, "Expected " + function.arity() + " arguments but got " + arguments.size() + ".");
        }

        return function.call(this, arguments, expr.paren);
    }

    @Override
    public Object visitGetExpr(Expr.Get expr) {
        Object object = evaluate(expr.object);
        if(object instanceof LoxInstance){
            return ((LoxInstance)object).get(expr.name);
        }

        throw new RuntimeError(expr.name, "Only instances have properties.");
    }

    @Override
    public Object visitGroupingExpr(Expr.Grouping expr) {
        return evaluate(expr.expression);
    }

    @Override
    public Object visitLiteralExpr(Expr.Literal expr) {
        return expr.value;
    }

    @Override
    public Object visitLogicalExpr(Expr.Logical expr) {
        Object left = evaluate(expr.left);

        if (expr.operator.type == TokenType.OR) {
            if (isTruthy(left)) return left;
        } else {
            if (!isTruthy(left)) return left;
        }

        return evaluate(expr.right);
    }

    @Override
    public Object visitUnaryExpr(Expr.Unary expr) {
        Object right = evaluate(expr.right);

        switch (expr.operator.type) {
            case BANG:
                return !isTruthy(right);
            case MINUS:
                checkNumberOperand(expr.operator, right);
                return -(double) right;
        }

        return null;
    }

    @Override
    public Object visitSetExpr(Expr.Set expr) {
        Object object = evaluate(expr.object);
        if(!(object instanceof LoxInstance)){
            throw new RuntimeError(expr.name, "Only instances have fields.");
        }

        Object value = evaluate(expr.value);
        ((LoxInstance)object).set(expr.name, value);
        return value;
    }

    @Override
    public Object visitSuperExpr(Expr.Super expr) {
        int distance = locals.get(expr);
        LoxClass superclass = (LoxClass)environment.getAt(distance, "super");
        LoxInstance object = (LoxInstance) environment.getAt(distance-1, "this");

        LoxFunction method = superclass.findMethod(expr.method.lexeme);

        if(method == null){
            throw new RuntimeError(expr.method, "Undefined property '" + expr.method.lexeme + "'.");
        }

        return method.bind(object);
    }

    @Override
    public Object visitThisExpr(Expr.This expr) {
        return lookUpVariable(expr.keyword, expr);
    }

    @Override
    public Object visitVariableExpr(Expr.Variable expr) {
        return lookUpVariable(expr.name, expr);
    }

    private void defineNative() {
        globals.define("clock", new LoxCallable() {
            @Override
            public int arity() {
                return 0;
            }

            @Override
            public Object call(Interpreter interpreter, List<Object> arguments, Token token) {
                return (double) System.currentTimeMillis() / 1000.0;
            }

            @Override
            public String toString() {
                return "<native fn>";
            }
        });
        globals.define("write", new LoxCallable() {
            @Override
            public int arity() {
                return 1;
            }

            @Override
            public Object call(Interpreter interpreter, List<Object> arguments, Token token) {
                System.out.print(stringify(arguments.get(0)));
                return null;
            }

            @Override
            public String toString() {
                return "<native fn>";
            }
        });
        globals.define("readLine", new LoxCallable() {
            @Override
            public int arity() {
                return 0;
            }

            @Override
            public Object call(Interpreter interpreter, List<Object> arguments, Token token) {
                try {
                    InputStreamReader input = new InputStreamReader(System.in);
                    BufferedReader reader = new BufferedReader(input);

                    return reader.readLine();
                } catch (IOException e) {
                    throw new RuntimeError(token, "Could not read standard input.");
                }
            }

            @Override
            public String toString() {
                return "<native fn>";
            }
        });
        globals.define("openFile", new LoxCallable() {
            @Override
            public int arity() {
                return 2;
            }

            @Override
            public Object call(Interpreter interpreter, List<Object> arguments, Token token) {
                String path;
                String mode;
                try {
                    path = (String) arguments.get(0);
                    mode = (String) arguments.get(1);
                } catch (Exception e) {
                    throw new RuntimeError(token, "Invalid argument type.");
                }
                try {
                    if (mode.equals("r")) {
                        return new BufferedReader(new FileReader(path));
                    }
                    if (mode.equals("w")) {
                        return new BufferedWriter(new FileWriter(path));
                    }
                    throw new RuntimeError(null, "Invalid mode.");
                } catch (IOException e) {
                    throw new RuntimeError(token, "Could not open file '" + path + "'.");
                }
            }

            @Override
            public String toString() {
                return "<native fn>";
            }
        });
        globals.define("fileReadLine", new LoxCallable() {
            @Override
            public int arity() {
                return 1;
            }

            @Override
            public Object call(Interpreter interpreter, List<Object> arguments, Token token) {
                if (!(arguments.get(0) instanceof BufferedReader)) {
                    throw new RuntimeError(token, "Argument must be a file opened for reading.");
                }
                try {
                    return ((BufferedReader) arguments.get(0)).readLine();
                } catch (IOException e) {
                    throw new RuntimeError(token, "Read error.");
                }
            }

            @Override
            public String toString() {
                return "<native fn>";
            }
        });
        globals.define("fileWrite", new LoxCallable() {
            @Override
            public int arity() {
                return 2;
            }

            @Override
            public Object call(Interpreter interpreter, List<Object> arguments, Token token) {
                if (!(arguments.get(0) instanceof BufferedWriter)) {
                    throw new RuntimeError(token, "Argument must be a file opened for writing.");
                }
                try {
                    ((Writer) arguments.get(0)).write(stringify(arguments.get(1)));
                } catch (IOException e) {
                    throw new RuntimeError(token, "Write error.");
                }
                return null;
            }

            @Override
            public String toString() {
                return "<native fn>";
            }
        });
        globals.define("closeFile", new LoxCallable() {
            @Override
            public int arity() {
                return 1;
            }

            @Override
            public Object call(Interpreter interpreter, List<Object> arguments, Token token) {
                try {
                    if (arguments.get(0) instanceof Reader || arguments.get(0) instanceof Writer) {
                        ((Closeable) arguments.get(0)).close();
                    }
                    return null;
                } catch (IOException e) {
                    throw new RuntimeError(token, "Could not close file.");
                }
            }

            @Override
            public String toString() {
                return "<native fn>";
            }
        });
        globals.define("threadCreate", new LoxCallable() {
            @Override
            public int arity() {
                return -1;
            }

            @Override
            public Object call(Interpreter interpreter, List<Object> arguments, Token token) {
                if (arguments.isEmpty() || !(arguments.get(0) instanceof LoxCallable)) {
                    throw new RuntimeError(token, "First argument to thread must be callable.");
                }

                LoxCallable function = (LoxCallable) arguments.get(0);
                List<Object> fnArguments = new ArrayList<>(arguments.subList(1, arguments.size()));

                if (function.arity() != -1 && function.arity() != fnArguments.size()) {
                    throw new RuntimeError(token, "Function passed to thread() expected '" + function.arity() + "' arguments but got '" + fnArguments.size() + "' instead.");
                }

                Thread t = new Thread(() -> {
                    try {
                        Interpreter threadInterpreter = new Interpreter(interpreter);
                        function.call(threadInterpreter, fnArguments, token);
                    } catch (RuntimeError error) {
                        Lox.runtimeError(error);
                    }
                });
                t.start();
                return t;
            }

            @Override
            public String toString() {
                return "<native fn>";
            }
        });
        globals.define("threadJoin", new LoxCallable() {
            @Override
            public int arity() {
                return 1;
            }

            @Override
            public Object call(Interpreter interpreter, List<Object> arguments, Token token) {
                if (!(arguments.get(0) instanceof Thread)) {
                    throw new RuntimeError(token, "Argument must be a thread instance.");
                }
                Thread t = (Thread) arguments.get(0);
                try {
                    t.join();
                } catch (InterruptedException e) {
                    throw new RuntimeError(token, "Thread join was interrupted.");
                }
                return null;
            }

            @Override
            public String toString() {
                return "<native fn>";
            }
        });
        globals.define("mutexInit", new LoxCallable() {
            @Override
            public int arity() {
                return 0;
            }

            @Override
            public Object call(Interpreter interpreter, List<Object> arguments, Token token) {
                return new ReentrantLock();
            }

            @Override
            public String toString() {
                return "<native fn>";
            }
        });
        globals.define("mutexLock", new LoxCallable() {
            @Override
            public int arity() {
                return 1;
            }

            @Override
            public Object call(Interpreter interpreter, List<Object> arguments, Token token) {
                if (!(arguments.get(0) instanceof ReentrantLock)) {
                    throw new RuntimeError(token, "Argument must be of type Mutex.");
                }
                ((ReentrantLock) arguments.get(0)).lock();
                return null;
            }

            @Override
            public String toString() {
                return "<native fn>";
            }
        });
        globals.define("mutexUnlock", new LoxCallable() {
            @Override
            public int arity() {
                return 1;
            }

            @Override
            public Object call(Interpreter interpreter, List<Object> arguments, Token token) {
                if (!(arguments.get(0) instanceof ReentrantLock)) {
                    throw new RuntimeError(token, "Argument must be of type Mutex.");
                }
                try {
                    ((ReentrantLock) arguments.get(0)).unlock();
                } catch (IllegalMonitorStateException e) {
                    throw new RuntimeError(token, "Can't unlock a mutex that isn't held by the current thread.");
                }
                return null;
            }

            @Override
            public String toString() {
                return "<native fn>";
            }
        });
    }

    private Object lookUpVariable(Token name, Expr expr){
        Integer distance = locals.get(expr);
        if(distance != null){
            return environment.getAt(distance, name.lexeme);
        }else{
            return globals.get(name);
        }
    }

    private void checkNumberOperand(Token operator, Object operand) {
        if (operand instanceof Double) return;
        throw new RuntimeError(operator, "Operand must be a number.");
    }

    private void checkNumberOperands(Token operator, Object left, Object right) {
        if (left instanceof Double && right instanceof Double) return;

        throw new RuntimeError(operator, "Operands must be numbers");
    }

    private boolean isTruthy(Object object) {
        if (object == null) return false;
        if (object instanceof Boolean) return (boolean) object;
        return true;
    }

    private boolean isEqual(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null) return false;

        return a.equals(b);
    }

    private String stringify(Object object) {
        if (object == null) return "nil";

        if (object instanceof Double) {
            String text = object.toString();
            if (text.endsWith(".0")) {
                text = text.substring(0, text.length() - 2);
            }
            return text;
        }

        return object.toString();
    }
}
