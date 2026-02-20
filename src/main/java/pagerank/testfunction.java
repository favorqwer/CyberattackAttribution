package pagerank; /**
 * Created by fang on 6/13/17.
 */
import java.math.BigDecimal;
import java.util.*;
public class testfunction {
    private static boolean isOverlap(BigDecimal[]a, BigDecimal[]b){
        if(a[1].compareTo(b[0])>=0 && a[1].compareTo(b[1])<=0 || a[0].compareTo(b[0])>=0 && a[0].compareTo(b[1])<=0){
            return true;
        }
        return false;
    }
    public static void main(String[] args) {
        String test = "9597(abc)";
        String[] a = test.split("\\(");
        for(int i=0;i<a.length;i++){
            System.out.println(a[i]);
        }
        String a2 = "1500593446";
        int aa = Integer.valueOf(a2);
        System.out.println(aa);
        BigDecimal[] arrA = {new BigDecimal(2),new BigDecimal(6)};
        BigDecimal[] arrB = {new BigDecimal(2),new BigDecimal(5)};
        System.out.println(testfunction.isOverlap(arrB,arrA));

        Set<Integer> setA = new HashSet<>(Arrays.asList(1,2,3,4,5));
        Set<Integer> setB = new HashSet<>(Arrays.asList(3,4,5,6));
        String[] arr = {"a","b","c"};
        Set<String> set = new HashSet<>(Arrays.asList(arr));
        for(int i=0;i<arr.length;i++){
            if(set.contains(arr[i])){
                System.out.println("true");
            }
        }

    }

}
