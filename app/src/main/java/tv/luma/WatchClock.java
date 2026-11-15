package tv.luma;

/** Active wall-clock viewing, independent of seek position and playback speed. */
public final class WatchClock {
    private long previous=-1,total,drained;
    private boolean active;
    public void update(long now,boolean playing){
        if(previous>=0&&active){long delta=now-previous;
            // A suspended process cannot establish whether video kept playing.
            if(delta>0&&delta<=5000)total+=delta;
        }
        previous=now;active=playing;
    }
    public long total(){return total;}
    public long drain(){long value=total-drained;drained=total;return value;}
    public void reset(){previous=-1;total=drained=0;active=false;}
}
