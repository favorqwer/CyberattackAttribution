## Size of files on *vm0* (in bytes)

Command for getting file size:

```bash
ls -l | awk '{print $9"   -    "$5}'
```


* /tmp/img 92433
* /var/vpnfilter 296592