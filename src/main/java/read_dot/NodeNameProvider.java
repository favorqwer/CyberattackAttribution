package read_dot;

import org.jgrapht.ext.ComponentNameProvider;

public class NodeNameProvider implements ComponentNameProvider<SimpleNode> {

    @Override
    public String getName(SimpleNode e) {
        String sig = e.signature;
        if(sig.startsWith("=")){
            sig = e.signature.substring(1);
        }
//        if(sig.length()>15){                                         //only for writing report
//            String[] pars = sig.split("/");
//            int l = pars.length;
//            sig = l>0? pars[l-1]:sig;
//        }
        return sig+" "+"["+ e.reputation+"]";
    }

}
