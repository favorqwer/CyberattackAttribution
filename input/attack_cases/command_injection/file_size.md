## Size of files on *vm0* (in bytes)

Command for getting file size:

```bash
ls -l | awk '{print $9"   -    "$5}'
```


### Attack stage

* /tmp/bdoor 30856


### Post exploitation behaviors

* /tmp/password_in_files.txt 20881



## Size of files on *vm1* (in bytes)

### Attack script

* /home/ubuntu/bin/attack.py 3065