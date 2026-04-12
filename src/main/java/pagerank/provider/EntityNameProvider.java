package pagerank.provider;

import pagerank.entity.EntityNode;

import java.util.function.Function;

public class EntityNameProvider implements Function<EntityNode, String> {

    @Override
    public String apply(EntityNode e) {
        String sig = e.getSignature();
        if (sig.startsWith("=")) {
            sig = e.getSignature().substring(1);
        }
        // if(sig.length()>15){ //only for writing report
        // String[] pars = sig.split("/");
        // int l = pars.length;
        // sig = l>0? pars[l-1]:sig;
        // }
        return sig + " " + "[" + e.reputation + "]";
    }

}
