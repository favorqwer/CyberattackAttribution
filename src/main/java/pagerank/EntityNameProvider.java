package pagerank;

import org.jgrapht.ext.ComponentNameProvider;

/**
 * Created by fang on 3/21/18.
 */
public class EntityNameProvider implements ComponentNameProvider<EntityNode> {

    @Override
    public String getName(EntityNode e) {
        String sig = e.getSignature();
        if(sig.startsWith("=")){
            sig = e.getSignature().substring(1);
        }
//        if(sig.length()>15){                                         //only for writing report
//            String[] pars = sig.split("/");
//            int l = pars.length;
//            sig = l>0? pars[l-1]:sig;
//        }
        return sig+" "+"["+ e.reputation+"]";
    }

}
