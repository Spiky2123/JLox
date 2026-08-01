package lox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class LoxInstance {
    private LoxClass klass;
    private final Map<String, Object> fields = new HashMap<>();

    LoxInstance(LoxClass klass){
        this.klass = klass;
    }

    Object get(Token name, Interpreter interpreter) {
        if (fields.containsKey(name.lexeme)) {
            return fields.get(name.lexeme);
        }

        LoxFunction method = klass.findMethod(name.lexeme);
        if (method != null)
        {
            LoxFunction bound = method.bind(this);
            if(bound.isGetter()){
                return bound.call(interpreter, new ArrayList<>());
            }
            return bound;
        }

        throw new RuntimeError(name, "Undefined property '" + name.lexeme + "'.");
    }

    void set(Token name, Object value){
        fields.put(name.lexeme, value);
    }

    @Override
    public String toString(){
        return klass.name + " instance";
    }
}
