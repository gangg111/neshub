# host (120 Hz vsync, PI pacing) <-> guest (59.52 Hz display, consumes records, sends pad s+8)
import random, collections
random.seed(1)
PER=16_666_667/1e6; SLACK=4.0; DELAY=8; TARGET=4
host_vs=1000/120; guest_vs=16.80
def net(): return random.uniform(3,12) + (random.random()<0.1)*random.uniform(5,30)
T=60_000.0  # ms
# events
arrive_pad={}      # step -> time pad reaches host
rec_arrive=[]      # (time record s reaches guest)
for s in range(DELAY): arrive_pad[s]=0.0
hstep=0; nextDue=0.0; hostPeriod=PER; leadAvg=TARGET; I=0.0
host_times=[]; waits=0
gstep=0; gq=collections.deque(); gtimes=[]
# simulate by stepping time on host vsyncs and guest vsyncs in order
ht=0.0; gt=0.0; primed=False
while min(ht,gt)<T:
    if ht<=gt:
        # host vsync
        if ht>=nextDue-SLACK:
            period=hostPeriod; nextDue+=period
            if arrive_pad.get(hstep,1e18)<=ht:
                host_times.append(ht)
                rec_arrive.append(ht+net()); 
                lead=max(arrive_pad)-hstep  # approx guestPadMax known
                known=[k for k,v in arrive_pad.items() if v<=ht]; lead=max(known)-hstep
                leadAvg+=(lead-leadAvg)*0.02; err=TARGET-leadAvg
                I=min(max(I+err*0.00002,0.0),0.03); adj=min(max(err*0.003+I,0.0),0.04)
                hostPeriod=PER*(1+adj); hstep+=1
            else:
                waits+=1; nextDue-=period
        ht+=host_vs
    else:
        # guest vsync: records that have arrived
        avail=sum(1 for t in rec_arrive[gstep:] if t<=gt)
        if not primed and avail>=2: primed=True
        if primed:
            if avail>0:
                gtimes.append(gt); arrive_pad[gstep+DELAY]=gt+net(); gstep+=1
                if avail>12:  # far behind
                    arrive_pad[gstep+DELAY]=gt+net(); gstep+=1
            else: primed=False
        gt+=guest_vs
import statistics
d=[b-a for a,b in zip(host_times,host_times[1:])][600:]
print('host fps %.3f waits/s %.2f'%(len(host_times)/(T/1000), waits/(T/1000)))
print('host frame gaps: 16.7=%d 25=%d 8.3=%d other=%d'%(sum(1 for x in d if abs(x-16.67)<1),sum(1 for x in d if abs(x-25)<1),sum(1 for x in d if abs(x-8.33)<1),sum(1 for x in d if not(abs(x-16.67)<1 or abs(x-25)<1 or abs(x-8.33)<1))))
g=[b-a for a,b in zip(gtimes,gtimes[1:])][600:]
print('guest frames gaps !=16.8: %d of %d'%(sum(1 for x in g if abs(x-16.8)>1),len(g)))
