#!/usr/bin/python
from sys import argv
from itertools import combinations,permutations

def vtoe(vfile,efile):
    a = []
    with open(vfile,"r") as v:
        a = v.read().strip().split(",")
        a = list(permutations(a,2))
    with open(efile,"w+") as e:
        for aa in a:
            e.write(",".join(aa)+"\n")

if __name__=='__main__':
    if(len(argv)!=3):
        print("Usage: prepare.py <vertex file> <edge file>")
    else:
        vtoe(argv[1],argv[2])
