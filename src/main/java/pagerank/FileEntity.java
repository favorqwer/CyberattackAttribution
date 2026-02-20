package pagerank;

import java.io.Serializable;

/**
 * Created by fang on 6/13/17.
 */
public class FileEntity extends Entity {
    private String user_ID;
    private  String group_ID;
    private String path;
    public FileEntity(double reputation,long id,int hopCount,String time1,String time2,String user_ID,
                String group_ID,String path,long uniqID){
        super(reputation,id,hopCount,time1,time2,uniqID);
        this.user_ID = user_ID;
        this.group_ID = group_ID;
        this.path = path;
    }

    public FileEntity() {}

    public String getUser_ID(){
        return user_ID;
    }

    public String getGroup_ID(){
        return group_ID;
    }

    public String getPath(){return path;}

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileEntity)) return false;

        FileEntity that = (FileEntity) o;

        if (user_ID != null ? !user_ID.equals(that.user_ID) : that.user_ID != null) return false;
        if (group_ID != null ? !group_ID.equals(that.group_ID) : that.group_ID != null) return false;
        return path.equals(that.path);
    }

    @Override
    public int hashCode() {
        int result = user_ID != null ? user_ID.hashCode() : 0;
        result = 31 * result + (group_ID != null ? group_ID.hashCode() : 0);
        result = 31 * result + path.hashCode();
        return result;
    }
}
