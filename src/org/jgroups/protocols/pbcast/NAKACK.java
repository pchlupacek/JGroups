package org.jgroups.protocols.pbcast;

import org.jgroups.*;
import org.jgroups.annotations.*;
import org.jgroups.conf.PropertyConverters;
import org.jgroups.protocols.TP;
import org.jgroups.stack.*;
import org.jgroups.util.BoundedList;
import org.jgroups.util.Digest;
import org.jgroups.util.TimeScheduler;
import org.jgroups.util.Util;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


/**
 * Negative AcKnowledgement layer (NAKs). Messages are assigned a monotonically
 * increasing sequence number (seqno). Receivers deliver messages ordered
 * according to seqno and request retransmission of missing messages.<br/>
 * Retransmit requests are usually sent to the original sender of a message, but
 * this can be changed by xmit_from_random_member (send to random member) or
 * use_mcast_xmit_req (send to everyone). Responses can also be sent to everyone
 * instead of the requester by setting use_mcast_xmit to true.
 *
 * @author Bela Ban
 */
@MBean(description="Reliable transmission multipoint FIFO protocol")
public class NAKACK extends Protocol implements Retransmitter.RetransmitCommand, NakReceiverWindow.Listener, TP.ProbeHandler {

    /** the weight with which we take the previous smoothed average into account, WEIGHT should be >0 and <= 1 */
    private static final double WEIGHT=0.9;

    private static final double INITIAL_SMOOTHED_AVG=30.0;

    private static final int NUM_REBROADCAST_MSGS=3;


    /* -----------------------------------------------------    Properties     --------------------- ------------------------------------ */


    @Property(name="retransmit_timeout", converter=PropertyConverters.LongArray.class, description="Timeout before requesting retransmissions. Default is 600, 1200, 2400, 4800")
    private long[] retransmit_timeouts= { 600, 1200, 2400, 4800 }; // time(s) to wait before requesting retransmission


    @Property(description="Max number of messages to be removed from a NakReceiverWindow. This property might " +
            "get removed anytime, so don't use it !")
    private int max_msg_batch_size=20000;
    
    /**
     * Retransmit messages using multicast rather than unicast. This has the advantage that, if many receivers
     * lost a message, the sender only retransmits once
     */
    @Property(description="Retransmit messages using multicast rather than unicast")
    private boolean use_mcast_xmit=true;

    /**
     * Use a multicast to request retransmission of missing messages. This may
     * be costly as every member in the cluster will send a response
     */
    @Property(description="Use a multicast to request retransmission of missing messages. Default is false")
    private boolean use_mcast_xmit_req=false;

    /**
     * Ask a random member for retransmission of a missing message. If set to
     * true, discard_delivered_msgs will be set to false
     */
    @Property(description="Ask a random member for retransmission of a missing message. Default is false")
    private boolean xmit_from_random_member=false;

    /**
     * The first value (in milliseconds) to use in the exponential backoff
     * retransmission mechanism. Only enabled if the value is > 0
     */
    @Property(description="The first value (in milliseconds) to use in the exponential backoff. Enabled if greater than 0. Default is 0")
    private long exponential_backoff=0;

    /**
     * If enabled, we use statistics gathered from actual retransmission times
     * to compute the new retransmission times
     */
    @Property(description="Use statistics gathered from actual retransmission times to compute new retransmission times. Default is false")
    private boolean use_stats_for_retransmission=false;

    @Property(description="Whether to use the old retransmitter which retransmits individual messages or the new one " +
            "which uses ranges of retransmitted messages. Default is true. Note that this property will be removed in 3.0; " +
            "it is only used to switch back to the old (and proven) retransmitter mechanism if issues occur")
    private boolean use_range_based_retransmitter=true;


    /**
     * Messages that have been received in order are sent up the stack (=
     * delivered to the application). Delivered messages are removed from
     * NakReceiverWindow.xmit_table and moved to
     * NakReceiverWindow.delivered_msgs, where they are later garbage collected
     * (by STABLE). Since we do retransmits only from sent messages, never
     * received or delivered messages, we can turn the moving to delivered_msgs
     * off, so we don't keep the message around, and don't need to wait for
     * garbage collection to remove them.
     */
    @Property(description="Should messages delivered to application be discarded")
    private boolean discard_delivered_msgs=false;

    @Property(description="Size of retransmission history. Default is 50 entries")
    private int xmit_history_max_size=50;

    @Property(description="Timeout to rebroadcast messages. Default is 2000 msec")
    private long max_rebroadcast_timeout=2000;

    /**
     * When not finding a message on an XMIT request, include the last N
     * stability messages in the error message
     */
    @Property(description="Should stability history be printed if we fail in retransmission. Default is false")
    protected boolean print_stability_history_on_failed_xmit=false;


    /** If true, logs messages discarded because received from other members */
    @Property(description="discards warnings about promiscuous traffic")
    private boolean log_discard_msgs=true;

    @Property(description="If true, trashes warnings about retransmission messages not found in the xmit_table (used for testing)")
    private boolean log_not_found_msgs=true;

    @Property(description="Number of rows of the matrix in the retransmission table (only for experts)",writable=false)
    int xmit_table_num_rows=5;

    @Property(description="Number of elements of a row of the matrix in the retransmission table (only for experts). " +
      "The capacity of the matrix is xmit_table_num_rows * xmit_table_msgs_per_row",writable=false)
    int xmit_table_msgs_per_row=10000;

    @Property(description="Resize factor of the matrix in the retransmission table (only for experts)",writable=false)
    double xmit_table_resize_factor=1.2;

    @Property(description="Number of milliseconds after which the matrix in the retransmission table " +
      "is compacted (only for experts)",writable=false)
    long xmit_table_max_compaction_time=10 * 60 * 1000;

    /* -------------------------------------------------- JMX ---------------------------------------------------------- */



    @ManagedAttribute(description="Number of retransmit requests received")
    private long xmit_reqs_received;
    @ManagedAttribute(description="Number of retransmit requests sent")
    private long xmit_reqs_sent;
    @ManagedAttribute(description="Number of retransmit responses received")
    private long xmit_rsps_received;
    @ManagedAttribute(description="Number of retransmit responses sent")
    private long xmit_rsps_sent;
    @ManagedAttribute(description="Number of missing messages received")
    private long missing_msgs_received;


    /** Captures stats on XMIT_REQS, XMIT_RSPS per sender */
    private ConcurrentMap<Address, StatsEntry> sent=Util.createConcurrentMap();

    /** Captures stats on XMIT_REQS, XMIT_RSPS per receiver */
    private ConcurrentMap<Address, StatsEntry> received=Util.createConcurrentMap();

    /** Per-sender map of seqnos and timestamps, to keep track of avg times for retransmission of messages */
    private final ConcurrentMap<Address, ConcurrentMap<Long, Long>> xmit_stats=Util.createConcurrentMap();

    /** Maintains a list of the last N retransmission times (duration to retransmit a message) for all members */
    private final ConcurrentMap<Address, BoundedList<Long>> xmit_times_history=Util.createConcurrentMap();

    /**
     * Maintains a smoothed average of the retransmission times per sender,
     * these are the actual values that are used for new retransmission requests
     */
    private final Map<Address,Double> smoothed_avg_xmit_times=new HashMap<Address,Double>();

    /** Keeps the last 50 retransmit requests */
    private final BoundedList<String> xmit_history=new BoundedList<String>(50);


    /* -------------------------------------------------    Fields    ------------------------------------------------------------------------- */

    private boolean is_server=false;
    private Address local_addr=null;
    private final List<Address> members=new CopyOnWriteArrayList<Address>();
    private View view;
    @GuardedBy("seqno_lock")
    private long seqno=0; // current message sequence number (starts with 1)
    private final Lock seqno_lock=new ReentrantLock();

    /** Map to store sent and received messages (keyed by sender) */
    private final ConcurrentMap<Address,NakReceiverWindow> xmit_table=Util.createConcurrentMap();

    private volatile boolean leaving=false;
    private volatile boolean running=false;
    private TimeScheduler timer=null;

    private final Lock rebroadcast_lock=new ReentrantLock();

    private final Condition rebroadcast_done=rebroadcast_lock.newCondition();

    // set during processing of a rebroadcast event
    private volatile boolean rebroadcasting=false;

    private final Lock rebroadcast_digest_lock=new ReentrantLock();
    @GuardedBy("rebroadcast_digest_lock")
    private Digest rebroadcast_digest=null;

    /** BoundedList<Digest>, keeps the last 10 stability messages */
    protected final BoundedList<Digest> stability_msgs=new BoundedList<Digest>(10);

    /** Keeps a bounded list of the last N digest sets */
    protected final BoundedList<String> digest_history=new BoundedList<String>(10);



    public NAKACK() {
    }


    public long getXmitRequestsReceived() {return xmit_reqs_received;}
    public long getXmitRequestsSent() {return xmit_reqs_sent;}
    public long getXmitResponsesReceived() {return xmit_rsps_received;}
    public long getXmitResponsesSent() {return xmit_rsps_sent;}
    public long getMissingMessagesReceived() {return missing_msgs_received;}

    @ManagedAttribute(description="Total number of missing messages")
    public int getPendingRetransmissionRequests() {
        int num=0;
        for(NakReceiverWindow win: xmit_table.values()) {
            num+=win.getPendingXmits();
        }
        return num;
    }

    @ManagedAttribute
    public int getXmitTableSize() {
        int num=0;
        for(NakReceiverWindow win: xmit_table.values()) {
            num+=win.size();
        }
        return num;
    }


    @ManagedAttribute
    public long getCurrentSeqno() {return seqno;}

    @ManagedOperation
    public String printRetransmitStats() {
        StringBuilder sb=new StringBuilder();
        for(Map.Entry<Address,NakReceiverWindow> entry: xmit_table.entrySet())
            sb.append(entry.getKey()).append(": ").append(entry.getValue().printRetransmitStats()).append("\n");
        return sb.toString();
    }

    public int getReceivedTableSize() {
        return getPendingRetransmissionRequests();
    }

    /**
     * Please don't use this method; it is only provided for unit testing !
     * @param mbr
     * @return
     */
    public NakReceiverWindow getWindow(Address mbr) {
        return xmit_table.get(mbr);
    }


    /**
     * Only used for unit tests, don't use !
     * @param timer
     */
    public void setTimer(TimeScheduler timer) {
        this.timer=timer;
    }

    public void resetStats() {
        xmit_reqs_received=xmit_reqs_sent=xmit_rsps_received=xmit_rsps_sent=missing_msgs_received=0;
        sent.clear();
        received.clear();
        stability_msgs.clear();
        digest_history.clear();
        xmit_history.clear();
    }

    public void init() throws Exception {
        if(xmit_from_random_member) {
            if(discard_delivered_msgs) {
                discard_delivered_msgs=false;
                log.warn("xmit_from_random_member set to true: changed discard_delivered_msgs to false");
            }
        }

        TP transport=getTransport();
        if(transport != null) {
            transport.registerProbeHandler(this);
            if(!transport.supportsMulticasting()) {
                if(use_mcast_xmit) {
                    log.warn("use_mcast_xmit should not be used because the transport (" + transport.getName() +
                            ") does not support IP multicasting; setting use_mcast_xmit to false");
                    use_mcast_xmit=false;
                }
                if(use_mcast_xmit_req) {
                    log.warn("use_mcast_xmit_req should not be used because the transport (" + transport.getName() +
                            ") does not support IP multicasting; setting use_mcast_xmit_req to false");
                    use_mcast_xmit_req=false;
                }
            }
        }
    }


    public boolean isUseMcastXmit() {
        return use_mcast_xmit;
    }

    public void setUseMcastXmit(boolean use_mcast_xmit) {
        this.use_mcast_xmit=use_mcast_xmit;
    }

    public boolean isXmitFromRandomMember() {
        return xmit_from_random_member;
    }

    public void setXmitFromRandomMember(boolean xmit_from_random_member) {
        this.xmit_from_random_member=xmit_from_random_member;
    }

    public boolean isDiscardDeliveredMsgs() {
        return discard_delivered_msgs;
    }

    public void setDiscardDeliveredMsgs(boolean discard_delivered_msgs) {
        this.discard_delivered_msgs=discard_delivered_msgs;
    }

    public void setLogDiscardMessages(boolean flag) {
        log_discard_msgs=flag;
    }

    public void setLogDiscardMsgs(boolean flag) {
        setLogDiscardMessages(flag);
    }

    public boolean getLogDiscardMessages() {
        return log_discard_msgs;
    }

    public Map<String,Object> dumpStats() {
        Map<String,Object> retval=super.dumpStats();
        retval.put("msgs", printMessages());
        return retval;
    }

    public String printStats() {
        StringBuilder sb=new StringBuilder();
        sb.append("sent:\n");
        for(Iterator<Map.Entry<Address, StatsEntry>> it=sent.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Address, StatsEntry> entry=it.next();
            Object key=entry.getKey();
            if(key == null || key == Global.NULL) key="<mcast dest>";
            StatsEntry val=entry.getValue();
            sb.append(key).append(": ").append(val).append("\n");
        }
        sb.append("\nreceived:\n");
        for(Iterator<Map.Entry<Address, StatsEntry>> it=received.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Address, StatsEntry> entry=it.next();
            Object key=entry.getKey();
            if(key == null || key == Global.NULL) key="<mcast dest>";
            StatsEntry val=entry.getValue();
            sb.append(key).append(": ").append(val).append("\n");
        }

        sb.append("\nStability messages received\n");
        sb.append(printStabilityMessages()).append("\n");

        return sb.toString();
    }

    @ManagedOperation(description="TODO")
    public String printStabilityMessages() {
        StringBuilder sb=new StringBuilder();
        sb.append(Util.printListWithDelimiter(stability_msgs, "\n"));
        return sb.toString();
    }

    public String printStabilityHistory() {
        StringBuilder sb=new StringBuilder();
        int i=1;
        for(Digest digest: stability_msgs) {
            sb.append(i++).append(": ").append(digest).append("\n");
        }
        return sb.toString();
    }

    @ManagedOperation(description="Keeps information about the last N times a digest was set or merged")
    public String printDigestHistory() {
        StringBuilder sb=new StringBuilder(local_addr + ":\n");
        for(String tmp: digest_history)
            sb.append(tmp).append("\n");
        return sb.toString();
    }

    @ManagedOperation(description="TODO")
    public String printLossRates() {
        StringBuilder sb=new StringBuilder();
        NakReceiverWindow win;
        for(Map.Entry<Address,NakReceiverWindow> entry: xmit_table.entrySet()) {
            win=entry.getValue();
            sb.append(entry.getKey()).append(": ").append(win.printLossRate()).append("\n");
        }
        return sb.toString();
    }

    @ManagedOperation(description="Returns the sizes of all NakReceiverWindow.RetransmitTables")
    public String printRetransmitTableSizes() {
        StringBuilder sb=new StringBuilder();
        for(Map.Entry<Address,NakReceiverWindow> entry: xmit_table.entrySet()) {
            NakReceiverWindow win=entry.getValue();
            sb.append(entry.getKey() + ": ").append(win.getRetransmiTableSize())
              .append(" (capacity=" + win.getRetransmitTableCapacity())
              .append(", fill factor=" + win.getRetransmitTableFillFactor() + "%)\n");
        }
        return sb.toString();
    }


    @ManagedOperation(description="Compacts the retransmission tables")
    public void compact() {
        for(Map.Entry<Address,NakReceiverWindow> entry: xmit_table.entrySet()) {
            NakReceiverWindow win=entry.getValue();
            win.compact();
        }
    }


    @ManagedAttribute
    public double getAverageLossRate() {
        double retval=0.0;
        int count=0;
        if(xmit_table.isEmpty())
            return 0.0;
        for(NakReceiverWindow win: xmit_table.values()) {
            retval+=win.getLossRate();
            count++;
        }
        return retval / (double)count;
    }

    @ManagedAttribute
    public double getAverageSmoothedLossRate() {
        double retval=0.0;
        int count=0;
        if(xmit_table.isEmpty())
            return 0.0;
        for(NakReceiverWindow win: xmit_table.values()) {
            retval+=win.getSmoothedLossRate();
            count++;
        }
        return retval / (double)count;
    }


    public Vector<Integer> providedUpServices() {
        Vector<Integer> retval=new Vector<Integer>(5);
        retval.addElement(new Integer(Event.GET_DIGEST));
        retval.addElement(new Integer(Event.SET_DIGEST));
        retval.addElement(new Integer(Event.OVERWRITE_DIGEST));
        retval.addElement(new Integer(Event.MERGE_DIGEST));
        return retval;
    }


    public void start() throws Exception {
        timer=getTransport().getTimer();
        if(timer == null)
            throw new Exception("timer is null");
        running=true;
        leaving=false;
    }


    public void stop() {
        running=false;
        reset();  // clears sent_msgs and destroys all NakReceiverWindows
    }


    /**
     * <b>Callback</b>. Called by superclass when event may be handled.<p> <b>Do not use <code>down_prot.down()</code> in this
     * method as the event is passed down by default by the superclass after this method returns !</b>
     */
    public Object down(Event evt) {
        switch(evt.getType()) {

            case Event.MSG:
                Message msg=(Message)evt.getArg();
                Address dest=msg.getDest();
                if(dest != null && !dest.isMulticastAddress() || msg.isFlagSet(Message.NO_RELIABILITY))
                    break; // unicast address: not null and not mcast, pass down unchanged

                send(evt, msg);
                return null;    // don't pass down the stack

            case Event.STABLE:  // generated by STABLE layer. Delete stable messages passed in arg
                stable((Digest)evt.getArg());
                return null;  // do not pass down further (Bela Aug 7 2001)

            case Event.GET_DIGEST:
                return getDigest();

            case Event.SET_DIGEST:
                setDigest((Digest)evt.getArg());
                return null;

            case Event.OVERWRITE_DIGEST:
                overwriteDigest((Digest)evt.getArg());
                return null;

            case Event.MERGE_DIGEST:
                mergeDigest((Digest)evt.getArg());
                return null;

            case Event.TMP_VIEW:
                View tmp_view=(View)evt.getArg();
                List<Address> mbrs=tmp_view.getMembers();
                members.clear();
                members.addAll(mbrs);
                break;

            case Event.VIEW_CHANGE:
                tmp_view=(View)evt.getArg();
                mbrs=tmp_view.getMembers();
                members.clear();
                members.addAll(mbrs);
                view=tmp_view;
                adjustReceivers(members);
                is_server=true;  // check vids from now on

                Set<Address> tmp=new LinkedHashSet<Address>(members);
                tmp.add(null); // for null destination (= mcast)
                sent.keySet().retainAll(tmp);
                received.keySet().retainAll(tmp);

                xmit_stats.keySet().retainAll(tmp);
                // in_progress.keySet().retainAll(mbrs); // remove elements which are not in the membership
                break;

            case Event.BECOME_SERVER:
                is_server=true;
                break;

            case Event.SET_LOCAL_ADDRESS:
                local_addr=(Address)evt.getArg();
                break;

            case Event.DISCONNECT:
                leaving=true;
                reset();
                break;

            case Event.REBROADCAST:
                rebroadcasting=true;
                rebroadcast_digest=(Digest)evt.getArg();
                try {
                    rebroadcastMessages();
                }
                finally {
                    rebroadcasting=false;
                    rebroadcast_digest_lock.lock();
                    try {
                        rebroadcast_digest=null;
                    }
                    finally {
                        rebroadcast_digest_lock.unlock();
                    }
                }
                return null;
        }

        return down_prot.down(evt);
    }




    /**
     * <b>Callback</b>. Called by superclass when event may be handled.<p> <b>Do not use <code>PassUp</code> in this
     * method as the event is passed up by default by the superclass after this method returns !</b>
     */
    public Object up(Event evt) {
        switch(evt.getType()) {

        case Event.MSG:
            Message msg=(Message)evt.getArg();
            if(msg.isFlagSet(Message.NO_RELIABILITY))
                break;
            NakAckHeader hdr=(NakAckHeader)msg.getHeader(this.id);
            if(hdr == null)
                break;  // pass up (e.g. unicast msg)

            if(!is_server) { // discard messages while not yet server (i.e., until JOIN has returned)
                if(log.isTraceEnabled())
                    log.trace("message " + msg.getSrc() + "::" + hdr.seqno + " was discarded (not yet server)");
                return null;
            }

            // Changed by bela Jan 29 2003: we must not remove the header, otherwise further xmit requests will fail !
            //hdr=(NakAckHeader)msg.removeHeader(getName());

            switch(hdr.type) {

            case NakAckHeader.MSG:
                handleMessage(msg, hdr);
                return null;        // transmitter passes message up for us !

            case NakAckHeader.XMIT_REQ:
                if(hdr.range == null) {
                    if(log.isErrorEnabled()) {
                        log.error("XMIT_REQ: range of xmit msg is null; discarding request from " + msg.getSrc());
                    }
                    return null;
                }
                handleXmitReq(msg.getSrc(), hdr.range.low, hdr.range.high, hdr.sender);
                return null;

            case NakAckHeader.XMIT_RSP:
                handleXmitRsp(msg, hdr);
                return null;

            default:
                if(log.isErrorEnabled()) {
                    log.error("NakAck header type " + hdr.type + " not known !");
                }
                return null;
            }

        case Event.STABLE:  // generated by STABLE layer. Delete stable messages passed in arg
            stable((Digest)evt.getArg());
            return null;  // do not pass up further (Bela Aug 7 2001)

        case Event.SUSPECT:
            // release the promise if rebroadcasting is in progress... otherwise we wait forever. there will be a new
            // flush round anyway
            if(rebroadcasting) {
                cancelRebroadcasting();
            }
            break;
        }
        return up_prot.up(evt);
    }






    /* --------------------------------- Private Methods --------------------------------------- */

    /**
     * Adds the message to the sent_msgs table and then passes it down the stack. Change Bela Ban May 26 2002: we don't
     * store a copy of the message, but a reference ! This saves us a lot of memory. However, this also means that a
     * message should not be changed after storing it in the sent-table ! See protocols/DESIGN for details.
     * Made seqno increment and adding to sent_msgs atomic, e.g. seqno won't get incremented if adding to
     * sent_msgs fails e.g. due to an OOM (see http://jira.jboss.com/jira/browse/JGRP-179). bela Jan 13 2006
     */
    private void send(Event evt, Message msg) {
        if(msg == null)
            throw new NullPointerException("msg is null; event is " + evt);

        if(!running) {
            if(log.isTraceEnabled())
                log.trace("[" + local_addr + "] discarded message as we're not in the 'running' state, message: " + msg);
            return;
        }

        long msg_id;
        NakReceiverWindow win=xmit_table.get(local_addr);
        if(win == null) {  // discard message if there is no entry for local_addr
            if(log.isWarnEnabled() && log_discard_msgs)
                log.warn(local_addr + ": discarded message from " + local_addr + " with no window, my view is " + view);
            return;
        }

        if(msg.getSrc() == null)
            msg.setSrc(local_addr); // this needs to be done so we can check whether the message sender is the local_addr

        seqno_lock.lock();
        try {
            try { // incrementing seqno and adding the msg to sent_msgs needs to be atomic
                msg_id=seqno +1;
                msg.putHeader(this.id, NakAckHeader.createMessageHeader(msg_id));
                win.add(msg_id, msg);
                seqno=msg_id;
            }
            catch(Throwable t) {
                throw new RuntimeException("failure adding msg " + msg + " to the retransmit table for " + local_addr, t);
            }
        }
        finally {
            seqno_lock.unlock();
        }

        try { // moved down_prot.down() out of synchronized clause (bela Sept 7 2006) http://jira.jboss.com/jira/browse/JGRP-300
            if(log.isTraceEnabled())
                log.trace("sending " + local_addr + "#" + msg_id);
            down_prot.down(evt); // if this fails, since msg is in sent_msgs, it can be retransmitted
        }
        catch(Throwable t) { // eat the exception, don't pass it up the stack
            if(log.isWarnEnabled()) {
                log.warn("failure passing message down", t);
            }
        }
    }



    /**
     * Finds the corresponding NakReceiverWindow and adds the message to it (according to seqno). Then removes as many
     * messages as possible from the NRW and passes them up the stack. Discards messages from non-members.
     */
    private void handleMessage(Message msg, NakAckHeader hdr) {
        Address sender=msg.getSrc();
        if(sender == null) {
            if(log.isErrorEnabled())
                log.error("sender of message is null");
            return;
        }

        NakReceiverWindow win=xmit_table.get(sender);
        if(win == null) {  // discard message if there is no entry for sender
            if(leaving)
                return;
            if(log.isWarnEnabled() && log_discard_msgs)
                log.warn(local_addr + ": dropped message from " + sender + " (not in table " + xmit_table.keySet() +"), view=" + view);
            return;
        }

        boolean loopback=local_addr.equals(sender);
        boolean added=loopback || win.add(hdr.seqno, msg);

        if(added && log.isTraceEnabled())
            log.trace(new StringBuilder().append(local_addr).append(": received ").append(sender).append('#').append(hdr.seqno));


        // OOB msg is passed up. When removed, we discard it. Affects ordering: http://jira.jboss.com/jira/browse/JGRP-379
        if(added && msg.isFlagSet(Message.OOB)) {
            if(loopback)
                msg=win.get(hdr.seqno); // we *have* to get a message, because loopback means we didn't add it to win !
            if(msg != null && msg.isFlagSet(Message.OOB)) {
                if(msg.setTransientFlagIfAbsent(Message.OOB_DELIVERED))
                    up_prot.up(new Event(Event.MSG, msg));
            }
        }

        // Efficient way of checking whether another thread is already processing messages from 'sender'.
        // If that's the case, we return immediately and let the existing thread process our message
        // (https://jira.jboss.org/jira/browse/JGRP-829). Benefit: fewer threads blocked on the same lock, these threads
        // can be returned to the thread pool
        final AtomicBoolean processing=win.getProcessing();
        if(!processing.compareAndSet(false, true)) {
            return;
        }

        boolean remove_msgs=discard_delivered_msgs && !loopback;
        boolean released_processing=false;
        try {
            while(true) {
                // we're removing a msg and set processing to false (if null) *atomically* (wrt to add())
                List<Message> msgs=win.removeMany(processing, remove_msgs, max_msg_batch_size);
                if(msgs == null || msgs.isEmpty()) {
                    released_processing=true;
                    if(rebroadcasting)
                        checkForRebroadcasts();
                    return;
                }

                for(final Message msg_to_deliver: msgs) {
                    // discard OOB msg if it has already been delivered (http://jira.jboss.com/jira/browse/JGRP-379)
                    if(msg_to_deliver.isFlagSet(Message.OOB) && !msg_to_deliver.setTransientFlagIfAbsent(Message.OOB_DELIVERED))
                        continue;

                    //msg_to_deliver.removeHeader(getName()); // Changed by bela Jan 29 2003: not needed (see above)
                    try {
                        up_prot.up(new Event(Event.MSG, msg_to_deliver));
                    }
                    catch(Throwable t) {
                        log.error("couldn't deliver message " + msg_to_deliver, t);
                    }
                }
            }
        }
        finally {
            // processing is always set in win.remove(processing) above and never here ! This code is just a
            // 2nd line of defense should there be an exception before win.remove(processing) sets processing
            if(!released_processing)
                processing.set(false);
        }
    }



    /**
     * Retransmits messsages first_seqno to last_seqno from original_sender from xmit_table to xmit_requester,
     * called when XMIT_REQ is received.
     * @param xmit_requester        The sender of the XMIT_REQ, we have to send the requested copy of the message to this address
     * @param first_seqno The first sequence number to be retransmitted (<= last_seqno)
     * @param last_seqno  The last sequence number to be retransmitted (>= first_seqno)
     * @param original_sender The member who originally sent the messsage. Guaranteed to be non-null
     */
    private void handleXmitReq(Address xmit_requester, long first_seqno, long last_seqno, Address original_sender) {
        if(first_seqno > last_seqno)
            return;

        if(log.isTraceEnabled()) {
            StringBuilder sb=new StringBuilder();
            sb.append(local_addr).append(": received xmit request from ").append(xmit_requester).append(" for ");
            sb.append(original_sender).append(" [").append(first_seqno).append(" - ").append(last_seqno).append("]");
            log.trace(sb.toString());
        }

        if(stats) {
            xmit_reqs_received+=last_seqno - first_seqno +1;
            updateStats(received, xmit_requester, 1, 0, 0);
        }

        NakReceiverWindow win=xmit_table.get(original_sender);
        if(win == null) {
            if(log.isErrorEnabled()) {
                StringBuilder sb=new StringBuilder();
                sb.append("(requester=").append(xmit_requester).append(", local_addr=").append(this.local_addr);
                sb.append(") ").append(original_sender).append(" not found in retransmission table");
                // don't print the table unless we are in trace mode because it can be LARGE
                if (log.isTraceEnabled()) {
                    sb.append(":\n").append(printMessages());
                } 
                if(print_stability_history_on_failed_xmit) {
                    sb.append(" (stability history:\n").append(printStabilityHistory());
                }
                log.error(sb.toString());
            }
            return;
        }

        long diff=last_seqno - first_seqno +1;
        if(diff >= 10) {
            List<Message> msgs=win.get(first_seqno, last_seqno);
            if(msgs != null) {
                for(Message msg: msgs)
                    sendXmitRsp(xmit_requester, msg);
            }
        }
        else {
            for(long i=first_seqno; i <= last_seqno; i++) {
                Message msg=win.get(i);
                if(msg == null) {
                    if(log.isWarnEnabled() && log_not_found_msgs && !local_addr.equals(xmit_requester)) {
                        StringBuilder sb=new StringBuilder();
                        sb.append("(requester=").append(xmit_requester).append(", local_addr=").append(this.local_addr);
                        sb.append(") message ").append(original_sender).append("::").append(i);
                        sb.append(" not found in retransmission table of ").append(original_sender).append(":\n").append(win);
                        if(print_stability_history_on_failed_xmit) {
                            sb.append(" (stability history:\n").append(printStabilityHistory());
                        }
                        log.warn(sb.toString());
                    }
                    continue;
                }
                sendXmitRsp(xmit_requester, msg);
            }
        }
    }



    private void cancelRebroadcasting() {
        rebroadcast_lock.lock();
        try {
            rebroadcasting=false;
            rebroadcast_done.signalAll();
        }
        finally {
            rebroadcast_lock.unlock();
        }
    }


    private static void updateStats(ConcurrentMap<Address,StatsEntry> map, Address key, int req, int rsp, int missing) {
        StatsEntry entry=map.get(key);
        if(entry == null) {
            entry=new StatsEntry();
            StatsEntry tmp=map.putIfAbsent(key, entry);
            if(tmp != null)
                entry=tmp;
        }
        entry.xmit_reqs+=req;
        entry.xmit_rsps+=rsp;
        entry.missing_msgs_rcvd+=missing;
    }



    /**
     * Sends a message msg to the requester. We have to wrap the original message into a retransmit message, as we need
     * to preserve the original message's properties, such as src, headers etc.
     * @param dest
     * @param msg
     */
    private void sendXmitRsp(Address dest, Message msg) {
        if(msg == null) {
            if(log.isErrorEnabled())
                log.error("message is null, cannot send retransmission");
            return;
        }

        if(stats) {
            xmit_rsps_sent++;
            updateStats(sent, dest, 0, 1, 0);
        }

        if(msg.getSrc() == null)
            msg.setSrc(local_addr);

        if(use_mcast_xmit) { // we simply send the original multicast message
            down_prot.down(new Event(Event.MSG, msg));
            return;
        }

        Message xmit_msg=msg.copy(true, true); // copy payload and headers
        xmit_msg.setDest(dest);
        NakAckHeader hdr=(NakAckHeader)xmit_msg.getHeader(id);
        hdr.type=NakAckHeader.XMIT_RSP; // change the type in the copy from MSG --> XMIT_RSP
        down_prot.down(new Event(Event.MSG, xmit_msg));
    }


    private void handleXmitRsp(Message msg, NakAckHeader hdr) {
        if(msg == null)
            return;

        try {
            if(stats) {
                xmit_rsps_received++;
                updateStats(received, msg.getSrc(), 0, 1, 0);
            }

            msg.setDest(null);
            hdr.type=NakAckHeader.MSG; // change the type back from XMIT_RSP --> MSG
            up(new Event(Event.MSG, msg));
            if(rebroadcasting)
                checkForRebroadcasts();
        }
        catch(Exception ex) {
            if(log.isErrorEnabled()) {
                log.error("failed reading retransmitted message", ex);
            }
        }
    }


    /**
     * Takes the argument highest_seqnos and compares it to the current digest. If the current digest has fewer messages,
     * then send retransmit messages for the missing messages. Return when all missing messages have been received. If
     * we're waiting for a missing message from P, and P crashes while waiting, we need to exclude P from the wait set.
     */
    private void rebroadcastMessages() {
        Digest my_digest;
        Map<Address,Digest.Entry> their_digest;
        Address sender;
        Digest.Entry their_entry, my_entry;
        long their_high, my_high;
        long sleep=max_rebroadcast_timeout / NUM_REBROADCAST_MSGS;
        long wait_time=max_rebroadcast_timeout, start=System.currentTimeMillis();

        while(wait_time > 0) {
            rebroadcast_digest_lock.lock();
            try {
                if(rebroadcast_digest == null)
                    break;
                their_digest=rebroadcast_digest.getSenders();
            }
            finally {
                rebroadcast_digest_lock.unlock();
            }
            my_digest=getDigest();

            boolean xmitted=false;
            for(Map.Entry<Address,Digest.Entry> entry: their_digest.entrySet()) {
                sender=entry.getKey();
                their_entry=entry.getValue();
                my_entry=my_digest.get(sender);
                if(my_entry == null)
                    continue;
                their_high=their_entry.getHighest();

                // Cannot ask for 0 to be retransmitted because the first seqno in NAKACK and UNICAST(2) is always 1 !
                // Also, we need to ask for retransmission of my_high+1, because we already *have* my_high, and don't
                // need it, so the retransmission range is [my_high+1 .. their_high]: *exclude* my_high, but *include*
                // their_high
                my_high=Math.max(1, my_entry.getHighest() +1);
                if(their_high > my_high) {
                    if(log.isTraceEnabled())
                        log.trace("[" + local_addr + "] fetching " + my_high + "-" + their_high + " from " + sender);
                    retransmit(my_high, their_high, sender, true); // use multicast to send retransmit request
                    xmitted=true;
                }
            }
            if(!xmitted)
                return; // we're done; no retransmissions are needed anymore. our digest is >= rebroadcast_digest

            rebroadcast_lock.lock();
            try {
                try {
                    my_digest=getDigest();
                    rebroadcast_digest_lock.lock();
                    try {
                        if(!rebroadcasting || my_digest.isGreaterThanOrEqual(rebroadcast_digest))
                            return;
                    }
                    finally {
                        rebroadcast_digest_lock.unlock();
                    }
                    rebroadcast_done.await(sleep, TimeUnit.MILLISECONDS);
                    wait_time-=(System.currentTimeMillis() - start);
                }
                catch(InterruptedException e) {
                }
            }
            finally {
                rebroadcast_lock.unlock();
            }
        }
    }

    protected void checkForRebroadcasts() {
        Digest tmp=getDigest();
        boolean cancel_rebroadcasting;
        rebroadcast_digest_lock.lock();
        try {
            cancel_rebroadcasting=tmp.isGreaterThanOrEqual(rebroadcast_digest);
        }
        finally {
            rebroadcast_digest_lock.unlock();
        }
        if(cancel_rebroadcasting) {
            cancelRebroadcasting();
        }
    }


    /**
     * Remove old members from NakReceiverWindows. Essentially removes all entries from xmit_table that are not
     * in <code>members</code>. This method is not called concurrently multiple times
     */
    private void adjustReceivers(List<Address> new_members) {
        for(Address member: xmit_table.keySet()) {
            if(!new_members.contains(member)) {
                if(local_addr != null && local_addr.equals(member))
                    continue;
                NakReceiverWindow win=xmit_table.remove(member);
                win.destroy();
                if(log.isDebugEnabled())
                    log.debug("removed " + member + " from xmit_table (not member anymore)");
            }
        }
    }


    /**
     * Returns a message digest: for each member P the lowest, highest delivered and highest received seqno is added
     */
    public Digest getDigest() {
        final Map<Address,Digest.Entry> map=new HashMap<Address,Digest.Entry>();
        for(Map.Entry<Address,NakReceiverWindow> entry: xmit_table.entrySet()) {
            Address sender=entry.getKey(); // guaranteed to be non-null (CCHM)
            NakReceiverWindow win=entry.getValue(); // guaranteed to be non-null (CCHM)
            long[] digest=win.getDigest();
            map.put(sender, new Digest.Entry(digest[0], digest[1], digest[2]));
        }
        return new Digest(map);
    }



    /**
     * Creates a NakReceiverWindow for each sender in the digest according to the sender's seqno. If NRW already exists,
     * reset it.
     */
    private void setDigest(Digest digest) {
        setDigest(digest, false);
    }


    /**
     * For all members of the digest, adjust the NakReceiverWindows in xmit_table. If no entry
     * exists, create one with the initial seqno set to the seqno of the member in the digest. If the member already
     * exists, and is not the local address, replace it with the new entry (http://jira.jboss.com/jira/browse/JGRP-699)
     * if the digest's seqno is greater than the seqno in the window.
     */
    private void mergeDigest(Digest digest) {
        setDigest(digest, true);
    }

    /**
     * Overwrites existing entries, but does NOT remove entries not found in the digest
     * @param digest
     */
    private void overwriteDigest(Digest digest) {
        if(digest == null)
            return;

        StringBuilder sb=new StringBuilder("\n[overwriteDigest()]\n");
        sb.append("existing digest:  " + getDigest()).append("\nnew digest:       " + digest);

        for(Map.Entry<Address, Digest.Entry> entry: digest.getSenders().entrySet()) {
            Address sender=entry.getKey();
            Digest.Entry val=entry.getValue();
            if(sender == null || val == null)
                continue;

            long highest_delivered_seqno=val.getHighestDeliveredSeqno();
            long low_seqno=val.getLow();

            NakReceiverWindow win=xmit_table.get(sender);
            if(win != null) {
                if(local_addr.equals(sender)) {
                    win.setHighestDelivered(highest_delivered_seqno);
                    continue; // don't destroy my own window
                }
                xmit_table.remove(sender);
                win.destroy(); // stops retransmission
            }
            win=createNakReceiverWindow(sender, highest_delivered_seqno, low_seqno);
            xmit_table.put(sender, win);
        }
        sb.append("\n").append("resulting digest: " + getDigest());
        digest_history.add(sb.toString());
        if(log.isDebugEnabled())
            log.debug(sb.toString());
    }


    /**
     * Sets or merges the digest. If there is no entry for a given member in xmit_table, create a new NakReceiverWindow.
     * Else skip the existing entry, unless it is a merge. In this case, skip the existing entry if its seqno is
     * greater than or equal to the one in the digest, or reset the window and create a new one if not.
     * @param digest The digest
     * @param merge Whether to merge the new digest with our own, or not
     */
    private void setDigest(Digest digest, boolean merge) {
        if(digest == null)
            return;

        StringBuilder sb=new StringBuilder(merge? "\n[mergeDigest()]\n" : "\n[setDigest()]\n");
        sb.append("existing digest:  " + getDigest()).append("\nnew digest:       " + digest);

        boolean set_own_seqno=false;
        for(Map.Entry<Address, Digest.Entry> entry: digest.getSenders().entrySet()) {
            Address sender=entry.getKey();
            Digest.Entry val=entry.getValue();
            if(sender == null || val == null)
                continue;

            long highest_delivered_seqno=val.getHighestDeliveredSeqno();
            long low_seqno=val.getLow();

            NakReceiverWindow win=xmit_table.get(sender);
            if(win != null) {
                // We only reset the window if its seqno is lower than the seqno shipped with the digest. Also, we
                // don't reset our own window (https://jira.jboss.org/jira/browse/JGRP-948, comment 20/Apr/09 03:39 AM)
                if(!merge
                        || (local_addr != null && local_addr.equals(sender)) // never overwrite our own entry
                        || win.getHighestDelivered() >= highest_delivered_seqno) // my seqno is >= digest's seqno for sender
                    continue;

                xmit_table.remove(sender);
                win.destroy(); // stops retransmission
                if(sender.equals(local_addr)) { // Adjust the seqno: https://jira.jboss.org/browse/JGRP-1251
                    seqno_lock.lock();
                    try {
                        seqno=highest_delivered_seqno;
                        set_own_seqno=true;
                    }
                    finally {
                        seqno_lock.unlock();
                    }
                }
            }
            win=createNakReceiverWindow(sender, highest_delivered_seqno, low_seqno);
            xmit_table.put(sender, win);
        }
        sb.append("\n").append("resulting digest: " + getDigest());
        if(set_own_seqno)
            sb.append("\nnew seqno for " + local_addr + ": " + seqno);
        digest_history.add(sb.toString());
        if(log.isDebugEnabled())
            log.debug(sb.toString());
    }


    private NakReceiverWindow createNakReceiverWindow(Address sender, long initial_seqno, long lowest_seqno) {
        NakReceiverWindow win=new NakReceiverWindow(sender, this, initial_seqno, lowest_seqno, timer, use_range_based_retransmitter,
                                                    xmit_table_num_rows, xmit_table_msgs_per_row,
                                                    xmit_table_resize_factor, xmit_table_max_compaction_time, false);

        if(use_stats_for_retransmission)
            win.setRetransmitTimeouts(new ActualInterval(sender));
        else if(exponential_backoff > 0)
            win.setRetransmitTimeouts(new ExponentialInterval(exponential_backoff));
        else
            win.setRetransmitTimeouts(new StaticInterval(retransmit_timeouts));
        if(stats)
            win.setListener(this);
        return win;
    }



    /**
     * Garbage collect messages that have been seen by all members. Update sent_msgs: for the sender P in the digest
     * which is equal to the local address, garbage collect all messages <= seqno at digest[P]. Update xmit_table:
     * for each sender P in the digest and its highest seqno seen SEQ, garbage collect all delivered_msgs in the
     * NakReceiverWindow corresponding to P which are <= seqno at digest[P].
     */
    private void stable(Digest digest) {
        NakReceiverWindow recv_win;
        long my_highest_rcvd;        // highest seqno received in my digest for a sender P
        long stability_highest_rcvd; // highest seqno received in the stability vector for a sender P

        if(members == null || local_addr == null || digest == null) {
            if(log.isWarnEnabled())
                log.warn("members, local_addr or digest are null !");
            return;
        }

        if(log.isTraceEnabled()) {
            log.trace("received stable digest " + digest);
        }

        stability_msgs.add(digest);

        Address sender;
        Digest.Entry val;
        long high_seqno_delivered, high_seqno_received;

        for(Map.Entry<Address, Digest.Entry> entry: digest.getSenders().entrySet()) {
            sender=entry.getKey();
            if(sender == null)
                continue;
            val=entry.getValue();
            high_seqno_delivered=val.getHighestDeliveredSeqno();
            high_seqno_received=val.getHighestReceivedSeqno();


            // check whether the last seqno received for a sender P in the stability vector is > last seqno
            // received for P in my digest. if yes, request retransmission (see "Last Message Dropped" topic
            // in DESIGN)
            recv_win=xmit_table.get(sender);
            if(recv_win != null) {
                my_highest_rcvd=recv_win.getHighestReceived();
                stability_highest_rcvd=high_seqno_received;

                if(stability_highest_rcvd >= 0 && stability_highest_rcvd > my_highest_rcvd) {
                    if(log.isTraceEnabled()) {
                        log.trace("my_highest_rcvd (" + my_highest_rcvd + ") < stability_highest_rcvd (" +
                                stability_highest_rcvd + "): requesting retransmission of " +
                                sender + '#' + stability_highest_rcvd);
                    }
                    retransmit(stability_highest_rcvd, stability_highest_rcvd, sender);
                }
            }

            if(high_seqno_delivered < 0)
                continue;

            if(log.isTraceEnabled())
                log.trace("deleting msgs <= " + high_seqno_delivered + " from " + sender);

            // delete *delivered* msgs that are stable
            if(recv_win != null)
                recv_win.stable(high_seqno_delivered);  // delete all messages with seqnos <= seqno
        }
    }



    /* ---------------------- Interface Retransmitter.RetransmitCommand ---------------------- */


    /**
     * Implementation of Retransmitter.RetransmitCommand. Called by retransmission thread when gap is detected.
     */
    public void retransmit(long first_seqno, long last_seqno, Address sender) {
        retransmit(first_seqno, last_seqno, sender, false);
    }



    protected void retransmit(long first_seqno, long last_seqno, final Address sender, boolean multicast_xmit_request) {
        NakAckHeader hdr;
        Message retransmit_msg;
        Address dest=sender; // to whom do we send the XMIT request ?

        if(multicast_xmit_request || this.use_mcast_xmit_req) {
            dest=null;
        }
        else {
            if(xmit_from_random_member && !local_addr.equals(sender)) {
                Address random_member=(Address)Util.pickRandomElement(members);
                if(random_member != null && !local_addr.equals(random_member)) {
                    dest=random_member;
                    if(log.isTraceEnabled())
                        log.trace("picked random member " + dest + " to send XMIT request to");
                }
            }
        }

        hdr=NakAckHeader.createXmitRequestHeader(first_seqno, last_seqno, sender);
        retransmit_msg=new Message(dest, null, null);
        retransmit_msg.setFlag(Message.OOB);
        if(log.isTraceEnabled())
            log.trace(local_addr + ": sending XMIT_REQ ([" + first_seqno + ", " + last_seqno + "]) to " + dest);
        retransmit_msg.putHeader(this.id, hdr);

        ConcurrentMap<Long,Long> tmp=xmit_stats.get(sender);
        if(tmp == null) {
            tmp=new ConcurrentHashMap<Long,Long>();
            ConcurrentMap<Long,Long> tmp2=xmit_stats.putIfAbsent(sender, tmp);
            if(tmp2 != null)
                tmp=tmp2;
        }
        for(long seq=first_seqno; seq < last_seqno; seq++) {
            tmp.putIfAbsent(seq, System.currentTimeMillis());
        }

        down_prot.down(new Event(Event.MSG, retransmit_msg));
        if(stats) {
            xmit_reqs_sent+=last_seqno - first_seqno +1;
            updateStats(sent, sender, 1, 0, 0);
        }

        xmit_history.add(sender + ": " + first_seqno + "-" + last_seqno);
    }
    /* ------------------- End of Interface Retransmitter.RetransmitCommand -------------------- */



    /* ----------------------- Interface NakReceiverWindow.Listener ---------------------- */

    public void missingMessageReceived(long seqno, final Address original_sender) {
        ConcurrentMap<Long,Long> tmp=xmit_stats.get(original_sender);
        if(tmp != null) {
            Long timestamp=tmp.remove(seqno);
            if(timestamp != null) {
                long diff=System.currentTimeMillis() - timestamp;
                BoundedList<Long> list=xmit_times_history.get(original_sender);
                if(list == null) {
                    list=new BoundedList<Long>(xmit_history_max_size);
                    BoundedList<Long> list2=xmit_times_history.putIfAbsent(original_sender, list);
                    if(list2 != null)
                        list=list2;
                }
                list.add(diff);

                // compute the smoothed average for retransmission times for original_sender
                // needs to be synchronized because we rely on the previous value for computation of the next value
                synchronized(smoothed_avg_xmit_times) {
                    Double smoothed_avg=smoothed_avg_xmit_times.get(original_sender);
                    if(smoothed_avg == null)
                        smoothed_avg=INITIAL_SMOOTHED_AVG;
                    // the smoothed avg takes 90% of the previous value, 100% of the new value and averages them
                    // then, we add 10% to be on the safe side (an xmit value should rather err on the higher than lower side)
                    smoothed_avg=((smoothed_avg * WEIGHT) + diff) / 2;
                    smoothed_avg=smoothed_avg * (2 - WEIGHT);
                    smoothed_avg_xmit_times.put(original_sender, smoothed_avg);
                }
            }
        }

        if(stats) {
            missing_msgs_received++;
            updateStats(received, original_sender, 0, 0, 1);
        }
    }

    /** Called when a message gap is detected */
    public void messageGapDetected(long from, long to, Address src) {
        ;
    }

    /* ------------------- End of Interface NakReceiverWindow.Listener ------------------- */

    private void reset() {
        seqno_lock.lock();
        try {
            seqno=0;
        }
        finally {
            seqno_lock.unlock();
        }

        for(NakReceiverWindow win: xmit_table.values()) {
            win.destroy();
        }
        xmit_table.clear();
    }


    @ManagedOperation(description="TODO")
    public String printMessages() {
        StringBuilder ret=new StringBuilder(local_addr + ":\n");
        for(Map.Entry<Address,NakReceiverWindow> entry: xmit_table.entrySet()) {
            Address addr=entry.getKey();
            NakReceiverWindow win=entry.getValue();
            ret.append(addr).append(": ").append(win.toString()).append('\n');
        }
        return ret.toString();
    }


    @ManagedOperation(description="TODO")
    public String printRetransmissionAvgs() {
        StringBuilder sb=new StringBuilder();

        for(Map.Entry<Address,BoundedList<Long>> entry: xmit_times_history.entrySet()) {
            Address sender=entry.getKey();
            BoundedList<Long> list=entry.getValue();
            long tmp=0;
            int i=0;
            for(Long val: list) {
                tmp+=val;
                i++;
            }
            double avg=i > 0? tmp / i: -1;
            sb.append(sender).append(": ").append(avg).append("\n");
        }
        return sb.toString();
    }

    @ManagedOperation(description="TODO")
    public String printSmoothedRetransmissionAvgs() {
        StringBuilder sb=new StringBuilder();
        for(Map.Entry<Address,Double> entry: smoothed_avg_xmit_times.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }

    @ManagedOperation(description="TODO")
    public String printRetransmissionTimes() {
        StringBuilder sb=new StringBuilder();

        for(Map.Entry<Address,BoundedList<Long>> entry: xmit_times_history.entrySet()) {
            Address sender=entry.getKey();
            BoundedList<Long> list=entry.getValue();
            sb.append(sender).append(": ").append(list).append("\n");
        }
        return sb.toString();
    }

    @ManagedOperation(description="Prints the last N retransmission requests")
    public String printXmitHistory() {
        StringBuilder sb=new StringBuilder();
        for(String req: xmit_history)
            sb.append(req).append("\n");
        return sb.toString();
    }

    @ManagedAttribute
    public double getTotalAverageRetransmissionTime() {
        long total=0;
        int i=0;

        for(BoundedList<Long> list: xmit_times_history.values()) {
            for(Long val: list) {
                total+=val;
                i++;
            }
        }
        return i > 0? total / i: -1;
    }

    @ManagedAttribute
    public double getTotalAverageSmoothedRetransmissionTime() {
        double total=0.0;
        int cnt=0;
        synchronized(smoothed_avg_xmit_times) {
            for(Double val: smoothed_avg_xmit_times.values()) {
                if(val != null) {
                    total+=val;
                    cnt++;
                }
            }
        }
        return cnt > 0? total / cnt : -1;
    }

    /** Returns the smoothed average retransmission time for a given sender */
    public double getSmoothedAverageRetransmissionTime(Address sender) {
        synchronized(smoothed_avg_xmit_times) {
            Double retval=smoothed_avg_xmit_times.get(sender);
            if(retval == null) {
                retval=INITIAL_SMOOTHED_AVG;
                smoothed_avg_xmit_times.put(sender, retval);
            }
            return retval;
        }
    }


    // ProbeHandler interface
    public Map<String, String> handleProbe(String... keys) {
        Map<String,String> retval=new HashMap<String,String>();
        for(String key: keys) {
            if(key.equals("digest-history"))
                retval.put(key, printDigestHistory());
            if(key.equals("dump-digest"))
                retval.put(key, "\n" + printMessages());
        }

        return retval;
    }

    // ProbeHandler interface
    public String[] supportedKeys() {
        return new String[]{"digest-history", "dump-digest"};
    }



    private class ActualInterval implements Interval {
        private final Address sender;

        public ActualInterval(Address sender) {
            this.sender=sender;
        }

        public long next() {
            return (long)getSmoothedAverageRetransmissionTime(sender);
        }

        public Interval copy() {
            return this;
        }
    }

    static class StatsEntry {
        long xmit_reqs, xmit_rsps, missing_msgs_rcvd;

        public String toString() {
            StringBuilder sb=new StringBuilder();
            sb.append(xmit_reqs).append(" xmit_reqs").append(", ").append(xmit_rsps).append(" xmit_rsps");
            sb.append(", ").append(missing_msgs_rcvd).append(" missing msgs");
            return sb.toString();
        }
    }


    /* ----------------------------- End of Private Methods ------------------------------------ */


}