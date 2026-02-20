## Size of files on *vm0* (in bytes)

Command for getting file size:

```bash
ls -l | awk '{print $9"   -    "$5}'
```


### Penetration
/tmp/bdoor 30856


### Password Crack
/tmp/update.jpg 1733067
/tmp/john.gpg 41234736
/usr/local/thttpd/www/cgi-bin/john.tbz2 41200194
/usr/local/thttpd/www/cgi-bin/john/password_crack.txt 48


### Data Leakage
/tmp/upload.tar 84357120
/tmp/upload.tar.bz2 36901881 (Note that after bzip2, its file size varies. After gpg, its file size becomes fixed.)
/tmp/upload 36901968
