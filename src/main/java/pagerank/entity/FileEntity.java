package pagerank.entity;

import java.io.Serializable;

/**
 * Created by fang on 6/13/17.
 */
public class FileEntity extends Entity {
    private String path;

    public FileEntity(double reputation, String path, long uniqID){
        super(reputation, uniqID);
        this.path = path;
    }

    public FileEntity() {}

    public String getPath(){return path;}

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileEntity)) return false;

        FileEntity that = (FileEntity) o;

        return path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }
}
