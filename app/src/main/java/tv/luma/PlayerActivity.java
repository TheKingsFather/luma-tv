package tv.luma;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.util.*;
import static tv.luma.Models.*;

/** Compatibility entry point; playback lives with the browser for seamless mini mode. */
public final class PlayerActivity extends Activity {
    public static final class Request {
        public final Title title;public final String videoId;public final List<Stream> streams;public final int index;
        public Request(Title t,String id,List<Stream> list,int index){title=t;videoId=id;streams=new ArrayList<>(list);this.index=index;}
    }
    @Override public void onCreate(Bundle state){super.onCreate(state);startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("playPending",true));finish();}
}
