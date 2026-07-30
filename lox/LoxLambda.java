package lox;

import java.util.List;

public class LoxLambda implements LoxCallable {
    private final Expr.Lambda declaration;
    private final Enviorment closure;

    LoxLambda(Expr.Lambda declaration, Enviorment closure) {
        this.closure = closure;
        this.declaration = declaration;
    }

    @Override
    public String toString(){
        return "<lambda>";
    }

    @Override
    public int arity() {
        return declaration.params.size();
    }

    @Override
    public Object call(Interpreter interpreter, List<Object> arguments) {
        Enviorment enviorment = new Enviorment(closure);
        for (int i = 0; i < declaration.params.size(); i++) {
            enviorment.define(declaration.params.get(i).lexeme, arguments.get(i));
        }

        try{
            interpreter.executeBlock(declaration.body, enviorment);
        } catch (Return returnValue){
            return returnValue.value;
        }
        return null;
    }
}
