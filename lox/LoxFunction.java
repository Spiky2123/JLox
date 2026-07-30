package lox;

import java.util.List;

public class LoxFunction implements LoxCallable {
    private final Stmt.Function declaration;
    private final Enviorment closure;

    LoxFunction(Stmt.Function declaration, Enviorment closure) {
        this.closure = closure;
        this.declaration = declaration;
    }

    @Override
    public String toString() {
        return "<fn " + declaration.name.lexeme + ">";
    }

    @Override
    public int arity() {
        return declaration.function.params.size();
    }

    @Override
    public Object call(Interpreter interpreter, List<Object> arguments) {
        Enviorment enviorment = new Enviorment(closure);
        for (int i = 0; i < declaration.function.params.size(); i++) {
            enviorment.define(declaration.function.params.get(i).lexeme, arguments.get(i));
        }

        try {
            interpreter.executeBlock(declaration.function.body, enviorment);
        } catch (Return returnValue) {
            return returnValue.value;
        }
        return null;
    }
}
