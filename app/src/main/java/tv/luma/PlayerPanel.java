package tv.luma;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.SingleSampleMediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import org.json.*;
import java.util.*;
import static tv.luma.Models.*;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlayerPanel {
    private static final float[] PLAYBACK_SPEEDS={0.75f,1f,1.25f,1.5f,1.75f,2f};
    private static final String[] SPEED_LABELS={"0.75×","1× (Normal)","1.25×","1.5×","1.75×","2×"};
    private static final int[] PICTURE_MODES={
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    };

    private final MainActivity activity;
    private boolean closed,mini,backgroundWasPlaying;
    private final List<Dialog> dialogs=new ArrayList<>();
    private FrameLayout miniBar;
    private TextView miniPlay,qualityLabel,miniQuality;
    private boolean isFinishing(){return closed||activity.isFinishing()||activity.isDestroyed();}
    private void finish(){activity.stopPlayer();}
    public FrameLayout view(){return root;}
    public boolean isMini(){return mini;}

    private LumaApp app;private Glass g;private PlayerActivity.Request request;private ExoPlayer player;private PlayerView video;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private FrameLayout root,controls,top;private TextView titleLabel,status,timeLabel,playButton,sourceLabel;
    private SeekBar seek;private int sourceIndex,serial=0,episodeCount=0;private boolean started,controlsVisible=true,dialogOpen,stopAtEnd=false,changingSource;
    private int sleepChoice;
    private long watchedSinceSync,lastSample,lastPosition,sleepAt;
    private String externalSub="";private final List<JSONObject> addonSubtitles=new ArrayList<>();
    private final Runnable hide=()->showControls(false);
    private final Runnable watchdog=()->{if(player!=null&&player.getPlaybackState()==Player.STATE_BUFFERING)failover();};
    private final Runnable ticker=new Runnable(){@Override public void run(){if(player==null)return;long now=SystemClock.elapsedRealtime();long pos=player.getCurrentPosition(),duration=player.getDuration();
        if(player.isPlaying()&&lastSample>0){long delta=pos-lastPosition;if(delta>0&&delta<3000)watchedSinceSync+=delta;}
        lastPosition=pos;lastSample=now;
        if(duration>0){timeLabel.setText(clock(pos)+"   /   "+clock(duration));if(!seek.isPressed())seek.setProgress((int)(pos*1000/duration));}
        if(watchedSinceSync>=15000)sync(false);
        if(sleepAt>0&&now>=sleepAt){sleepAt=0;sleepChoice=0;player.pause();showControls(true);g.notice("Sleep timer finished");}
        handler.postDelayed(this,1000);
    }};
    public PlayerPanel(MainActivity activity,Title title,String id,List<Stream> streams,int index){
        this.activity=activity;app=(LumaApp)activity.getApplication();request=new PlayerActivity.Request(title,id,streams,index);app.playback=request;g=activity.glass();sourceIndex=index;
        build();prepare(app.resume(title.id,id));loadSubtitles();handler.post(ticker);
    }
    public void setMini(boolean value){
        mini=value;handler.removeCallbacks(hide);top.setVisibility(value?View.GONE:View.VISIBLE);controls.setVisibility(value?View.GONE:View.VISIBLE);miniBar.setVisibility(value?View.VISIBLE:View.GONE);controlsVisible=!value;
        FrameLayout.LayoutParams p=value?new FrameLayout.LayoutParams(g.u(416),g.u(282)):new FrameLayout.LayoutParams(-1,-1);
        if(value){p.gravity=Gravity.BOTTOM|Gravity.RIGHT;p.rightMargin=g.u(30);p.bottomMargin=g.u(40);}
        root.setLayoutParams(p);root.setElevation(value?g.u(30):0);root.setClipToOutline(value);if(value)g.material(root,20);else root.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams v=new FrameLayout.LayoutParams(-1,-1);v.bottomMargin=value?g.u(48):0;video.setLayoutParams(v);applyPictureMode();
        FrameLayout.LayoutParams st=new FrameLayout.LayoutParams(-1,g.u(value?50:72));st.gravity=Gravity.CENTER;status.setLayoutParams(st);status.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,g.u(value?12:18));
        if(value)root.clearFocus();else showControls(true);
    }
    private void build(){
        root=new FrameLayout(activity);root.setBackgroundColor(Color.BLACK);video=new PlayerView(activity);video.setUseController(false);video.setFocusable(false);video.setFocusableInTouchMode(false);video.setKeepScreenOn(true);root.setKeepScreenOn(true);root.addView(video,new FrameLayout.LayoutParams(-1,-1));applyPictureMode();
        top=new FrameLayout(activity);g.material(top,25);g.at(root,top,1168,76,56,35);
        TextView back=g.button("←",false,this::finish);back.setPadding(0,0,0,0);g.at(top,back,46,46,15,15);
        String title=request.title.name;String episode="";for(Video v:request.title.videos())if(v.id.equals(request.videoId)){episode="  ·  S"+v.season+" E"+v.episode;break;}
        titleLabel=g.text(title+episode,20,Glass.WHITE,true);titleLabel.setMaxLines(1);titleLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);g.at(top,titleLabel,700,30,80,12);
        sourceLabel=g.text("",12,Glass.MUTED,false);sourceLabel.setMaxLines(1);sourceLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);g.at(top,sourceLabel,700,23,80,44);
        TextView playback=g.button("Playback",false,this::playbackMenu);
        g.at(top,playback,156,46,795,15);
        TextView miniButton=g.button("Mini player",false,activity::miniPlayer);g.at(top,miniButton,180,46,967,15);
        status=g.text("Opening your source…",18,Glass.MINT,true);status.setGravity(Gravity.CENTER);g.at(root,status,1020,72,130,305);
        controls=new FrameLayout(activity);g.material(controls,28);g.at(root,controls,1168,144,56,535);
        seek=new SeekBar(activity);seek.setMax(1000);seek.setProgressTintList(android.content.res.ColorStateList.valueOf(Glass.MINT));seek.setThumbTintList(android.content.res.ColorStateList.valueOf(Glass.MINT));g.at(controls,seek,1008,33,31,14);
        qualityLabel=resolutionBadge(15,"luma.resolution.full");g.material(qualityLabel,10);g.at(controls,qualityLabel,84,33,1053,14);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){boolean user;public void onStartTrackingTouch(SeekBar b){user=true;handler.removeCallbacks(hide);}public void onStopTrackingTouch(SeekBar b){user=false;delayHide();}public void onProgressChanged(SeekBar b,int p,boolean fromUser){if(fromUser&&player!=null&&player.getDuration()>0){player.seekTo(player.getDuration()*p/1000);delayHide();}}});
        playButton=g.button("Ⅱ  Pause",false,()->{if(player!=null){player.setPlayWhenReady(!player.getPlayWhenReady());delayHide();}});g.at(controls,playButton,145,47,28,68);
        timeLabel=g.text("00:00   /   --:--",15,Glass.WHITE,false);timeLabel.setGravity(Gravity.CENTER_VERTICAL);g.at(controls,timeLabel,210,46,194,68);
        TextView audio=g.button("Audio & subtitles",false,this::trackMenu);g.at(controls,audio,220,47,437,68);
        TextView sources=g.button("Sources",false,this::sourceMenu);g.at(controls,sources,137,47,673,68);
        TextView sleep=g.button("Sleep",false,this::sleepMenu);g.at(controls,sleep,116,47,826,68);
        TextView next=g.button("Next  →",false,()->nextEpisode(false));g.at(controls,next,165,47,958,68);
        miniBar=new FrameLayout(activity);g.material(miniBar,18);FrameLayout.LayoutParams bar=new FrameLayout.LayoutParams(-1,g.u(48));bar.gravity=Gravity.BOTTOM;root.addView(miniBar,bar);miniBar.setVisibility(View.GONE);
        miniPlay=g.button("Ⅱ",false,()->{if(player!=null)player.setPlayWhenReady(!player.getPlayWhenReady());});miniPlay.setPadding(0,0,0,0);miniPlay.setContentDescription("Pause or resume");g.at(miniBar,miniPlay,50,40,6,4);
        TextView rewind=g.button("−10",false,()->seekBy(-10000));rewind.setPadding(0,0,0,0);g.at(miniBar,rewind,54,40,62,4);
        TextView forward=g.button("+10",false,()->seekBy(10000));forward.setPadding(0,0,0,0);g.at(miniBar,forward,54,40,122,4);
        TextView mute=g.button("Mute",false,()->{if(player!=null){boolean muted=player.getVolume()>0;player.setVolume(muted?0:1);((TextView)miniBar.findViewWithTag("mute")).setText(muted?"Sound":"Mute");}});mute.setTag("mute");mute.setPadding(0,0,0,0);g.at(miniBar,mute,62,40,182,4);
        miniQuality=resolutionBadge(12,"luma.resolution.mini");g.at(miniBar,miniQuality,68,40,250,4);
        TextView restore=g.button("↗",false,activity::restorePlayer);restore.setPadding(0,0,0,0);restore.setContentDescription("Restore full player");g.at(miniBar,restore,40,40,324,4);
        TextView close=g.button("×",false,this::finish);close.setPadding(0,0,0,0);close.setContentDescription("Stop mini player");g.at(miniBar,close,40,40,370,4);
        playButton.requestFocus();delayHide();
    }
    private TextView resolutionBadge(float size,String tag){
        TextView badge=g.text("—",size,Glass.MINT,true);badge.setTag(tag);badge.setGravity(Gravity.CENTER);badge.setMaxLines(1);badge.setFocusable(false);badge.setContentDescription("Video resolution unavailable");return badge;
    }
    private void updateResolution(VideoSize size){
        boolean known=size.width>0&&size.height>0;
        String label=known?size.height+"p":"—";
        String detail=known?size.width+" × "+size.height+" · Current video resolution":"Video resolution unavailable";
        for(TextView badge:new TextView[]{qualityLabel,miniQuality}){if(!label.contentEquals(badge.getText()))badge.setText(label);badge.setContentDescription(detail);}
    }
    private void prepare(long position){
        if(isFinishing())return;int token=++serial;changingSource=true;handler.removeCallbacks(hide);
        if(player!=null){video.setPlayer(null);player.release();player=null;}
        updateResolution(VideoSize.UNKNOWN);
        Stream stream=request.streams.get(sourceIndex);sourceLabel.setText(stream.provider+"  ·  "+stream.name.replace('\n',' '));status.setText("Opening your source…");status.setVisibility(View.VISIBLE);
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setUserAgent("LumaTV/0.3").setConnectTimeoutMs(12000).setReadTimeoutMs(15000).setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(stream.headers);
        DefaultDataSource.Factory data=new DefaultDataSource.Factory(activity,http);
        player=new ExoPlayer.Builder(activity).setMediaSourceFactory(new DefaultMediaSourceFactory(data)).build();player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true);player.setHandleAudioBecomingNoisy(true);video.setPlayer(player);applyPlaybackSpeed();
        TrackSelectionParameters prefs=player.getTrackSelectionParameters().buildUpon().setPreferredAudioLanguage(app.settings.getString("audioLanguage","en")).setPreferredTextLanguage(app.settings.getString("subtitleLanguage","en")).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,!app.settings.getBoolean("subtitles",true)).build();player.setTrackSelectionParameters(prefs);
        player.addListener(new Player.Listener(){
            @Override public void onPlaybackStateChanged(int state){if(token!=serial)return;
                if(state==Player.STATE_READY){started=true;changingSource=false;status.setVisibility(View.GONE);handler.removeCallbacks(watchdog);}
                else if(state==Player.STATE_BUFFERING){status.setText("Finding the perfect frame…");status.setVisibility(View.VISIBLE);handler.removeCallbacks(watchdog);handler.postDelayed(watchdog,25000);}
                else if(state==Player.STATE_ENDED){updateResolution(VideoSize.UNKNOWN);sync(true);if(liveType(request.title.type)){finish();return;}if(stopAtEnd){finish();return;}if(app.settings.getBoolean("autoplay",true)&&nextVideo()!=null)nextEpisode(true);else{showControls(true);status.setText("That’s a wrap.");status.setVisibility(View.VISIBLE);}}
            }
            @Override public void onVideoSizeChanged(VideoSize size){if(token==serial&&!isFinishing())updateResolution(size);}
            @Override public void onTracksChanged(Tracks tracks){if(token==serial&&!isFinishing()&&!tracks.isTypeSelected(C.TRACK_TYPE_VIDEO))updateResolution(VideoSize.UNKNOWN);}
            @Override public void onIsPlayingChanged(boolean playing){
                if(token!=serial||isFinishing())return;
                playButton.setText(playing?"Ⅱ  Pause":"▶  Play");if(miniPlay!=null)miniPlay.setText(playing?"Ⅱ":"▶");
                delayHide();
            }
            @Override public void onPlayerError(PlaybackException error){if(token==serial){updateResolution(VideoSize.UNKNOWN);failover();}}
        });
        MediaItem.Builder item=new MediaItem.Builder().setUri(stream.url).setMediaMetadata(new MediaMetadata.Builder().setTitle(request.title.name).build());
        MediaSource media=new DefaultMediaSourceFactory(data).createMediaSource(item.build());
        if(!externalSub.isEmpty()){String lower=externalSub.toLowerCase(Locale.ROOT).split("\\?")[0];String mime=lower.endsWith(".vtt")?MimeTypes.TEXT_VTT:lower.endsWith(".ass")||lower.endsWith(".ssa")?MimeTypes.TEXT_SSA:MimeTypes.APPLICATION_SUBRIP;
            MediaItem.SubtitleConfiguration sub=new MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(externalSub)).setMimeType(mime).setLanguage("en").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build();item.setSubtitleConfigurations(Collections.singletonList(sub));}
        if(!externalSub.isEmpty()){
            // Add-on playback headers belong to the video provider, never a subtitle host.
            DataSource.Factory subtitles=new DefaultDataSource.Factory(activity,new DefaultHttpDataSource.Factory().setUserAgent("LumaTV/0.3"));
            MediaSource subtitle=new SingleSampleMediaSource.Factory(subtitles).createMediaSource(item.build().localConfiguration.subtitleConfigurations.get(0),C.TIME_UNSET);
            media=new MergingMediaSource(media,subtitle);
        }
        player.setMediaSource(media);player.seekTo(Math.max(0,position));player.prepare();player.play();lastSample=0;lastPosition=position;
    }
    private void failover(){
        if(isFinishing()||dialogOpen)return;long position=player==null?0:player.getCurrentPosition();
        updateResolution(VideoSize.UNKNOWN);
        if(sourceIndex+1<request.streams.size()){sourceIndex++;g.notice("Trying the next source…");prepare(position);return;}
        if(player!=null)player.pause();status.setVisibility(View.GONE);showControls(true);
        show(new GlassDialog(g,"This source couldn’t play","Try another source, or check the provider in Stremio.")
                .action("Back to title",false,this::finish,false).action("Choose source",true,this::sourceMenu,true));
    }
    private void sourceMenu(){
        List<GlassDialog.Choice> choices=new ArrayList<>();
        for(Stream stream:request.streams)choices.add(new GlassDialog.Choice(stream.name,stream.provider+" · "+stream.description));
        show(GlassDialog.choices(g,"Choose a source",request.title.name,choices,sourceIndex,index->{
            long position=player==null?0:player.getCurrentPosition();sourceIndex=index;prepare(position);delayHide();
        }));
    }
    private void playbackMenu() {
        if(liveType(request.title.type)) {
            pictureMenu();
            return;
        }
        show(GlassDialog.choices(g,"Playback","",new String[]{"Playback speed","Picture size"},-1,index->{
            if(index==0) speedMenu(); else pictureMenu();
        }));
    }

    private int speedChoice() {
        int saved=app.settings.getInt("playbackSpeed",1);
        return saved>=0&&saved<PLAYBACK_SPEEDS.length?saved:1;
    }

    private void applyPlaybackSpeed() {
        if(player!=null) {
            player.setPlaybackSpeed(liveType(request.title.type)?1f:PLAYBACK_SPEEDS[speedChoice()]);
        }
    }

    private void speedMenu() {
        show(GlassDialog.choices(g,"Playback speed","Luma remembers this speed for movies and episodes.",
                SPEED_LABELS,speedChoice(),index->{
                    app.settings.edit().putInt("playbackSpeed",index).apply();
                    applyPlaybackSpeed();
                }));
    }

    private int pictureChoice() {
        int saved=app.settings.getInt("pictureSize",0);
        return saved>=0&&saved<PICTURE_MODES.length?saved:0;
    }

    private void applyPictureMode() {
        video.setResizeMode(PICTURE_MODES[mini?0:pictureChoice()]);
    }

    private void pictureMenu() {
        List<GlassDialog.Choice> choices=Arrays.asList(
                new GlassDialog.Choice("Fit","Keep the whole picture at its original proportions."),
                new GlassDialog.Choice("Zoom","Fill the screen and crop the edges if needed."),
                new GlassDialog.Choice("Stretch","Fill the screen by changing the picture proportions."));
        show(GlassDialog.choices(g,"Picture size","Applies to the full-screen player.",choices,
                pictureChoice(),index->{
                    app.settings.edit().putInt("pictureSize",index).apply();
                    applyPictureMode();
                }));
    }

    private void trackMenu(){
        show(GlassDialog.choices(g,"Audio & subtitles","Choose the audio or subtitle track for this video.",new String[]{"Audio tracks","Embedded subtitles","Add-on subtitles"},-1,index->{
            if(index==0)chooseTrack(C.TRACK_TYPE_AUDIO);else if(index==1)chooseTrack(C.TRACK_TYPE_TEXT);else chooseExternalSubtitle();
        }));
    }
    private void chooseTrack(int type){
        if(player==null)return;
        List<GlassDialog.Choice> choices=new ArrayList<>();List<TrackSelectionOverride> overrides=new ArrayList<>();
        int selected=-1;boolean disabled=player.getTrackSelectionParameters().disabledTrackTypes.contains(type);
        if(type==C.TRACK_TYPE_TEXT){choices.add(new GlassDialog.Choice("Off","Hide subtitles for this video."));overrides.add(null);if(disabled)selected=0;}
        for(Tracks.Group group:player.getCurrentTracks().getGroups())if(group.getType()==type)for(int n=0;n<group.length;n++){
            if(!group.isTrackSupported(n))continue;Format format=group.getTrackFormat(n);
            String language=format.language==null?"Unknown language":new Locale(format.language).getDisplayLanguage();
            String label=format.label!=null?format.label:language;
            choices.add(new GlassDialog.Choice(label,language+(format.channelCount>0?" · "+format.channelCount+" channels":"")));
            overrides.add(new TrackSelectionOverride(group.getMediaTrackGroup(),n));
            if(!disabled&&group.isTrackSelected(n))selected=choices.size()-1;
        }
        if(choices.isEmpty()){show(GlassDialog.message(g,"Audio tracks","No supported audio tracks are available from this source."));return;}
        show(GlassDialog.choices(g,type==C.TRACK_TYPE_TEXT?"Embedded subtitles":"Audio tracks","Tracks included with your current source.",choices,selected,index->{
            if(player==null)return;TrackSelectionParameters.Builder builder=player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(type);
            TrackSelectionOverride override=overrides.get(index);builder.setTrackTypeDisabled(type,override==null);
            if(override!=null)builder.setOverrideForType(override);player.setTrackSelectionParameters(builder.build());
        }));
    }
    private void loadSubtitles(){final String id=request.videoId;app.io.execute(()->{try{List<JSONObject> list=app.api.subtitles(app.addons,request.title.type,id);handler.post(()->{if(isFinishing()||!request.videoId.equals(id))return;addonSubtitles.clear();addonSubtitles.addAll(list);});}catch(Exception ignored){}});}
    private void chooseExternalSubtitle(){
        if(addonSubtitles.isEmpty()){show(GlassDialog.message(g,"Add-on subtitles","No add-on subtitles found yet. Try embedded subtitles or check your Stremio subtitle add-ons."));return;}
        List<GlassDialog.Choice> choices=new ArrayList<>();List<JSONObject> subtitles=new ArrayList<>(addonSubtitles);
        choices.add(new GlassDialog.Choice("Use embedded subtitles","Switch back to the tracks included in this source."));int selected=externalSub.isEmpty()?0:-1;
        for(JSONObject sub:subtitles){String language=text(sub,"lang");choices.add(new GlassDialog.Choice(new Locale(language).getDisplayLanguage(),text(sub,"id")));if(externalSub.equals(text(sub,"url")))selected=choices.size()-1;}
        show(GlassDialog.choices(g,"Add-on subtitles","Subtitles from your connected Stremio add-ons.",choices,selected,index->{
            if(player==null)return;externalSub=index==0?"":text(subtitles.get(index-1),"url");long position=player.getCurrentPosition();prepare(position);
        }));
    }
    private void sleepMenu(){
        show(GlassDialog.choices(g,"Sleep timer","Choose when playback should stop.",new String[]{"Off","After this episode","In 30 minutes","In 60 minutes"},sleepChoice,index->{
            sleepChoice=index;sleepAt=index>=2?SystemClock.elapsedRealtime()+(index==2?1800000L:3600000L):0;stopAtEnd=index==1;g.notice(index==0?"Sleep timer off":"Sleep timer set");
        }));
    }
    private Video nextVideo(){List<Video> videos=request.title.videos();for(int i=0;i<videos.size()-1;i++)if(videos.get(i).id.equals(request.videoId)){for(int j=i+1;j<videos.size();j++)if(!videos.get(j).isFuture())return videos.get(j);break;}return null;}
    private void nextEpisode(boolean automatic){Video next=nextVideo();if(next==null){g.notice("You’re all caught up");return;}
        if(automatic&&episodeCount>=2){show(new GlassDialog(g,"Still watching?","Your next episode is ready when you are.").action("Back to title",false,this::finish,false).action("Keep watching",true,()->{episodeCount=0;nextEpisode(false);},true));return;}
        status.setText("Finding the next episode…");status.setVisibility(View.VISIBLE);final int token=++serial;Title title=request.title;app.io.execute(()->{try{StreamResult result=app.api.streams(app.addons,title,next.id,next.json.optJSONArray("streams"));handler.post(()->{if(isFinishing()||token!=serial)return;if(result.streams.isEmpty()){status.setText("No source for the next episode. Try it from the title page.");showControls(true);return;}sync(false);request=new PlayerActivity.Request(title,next.id,result.streams,0);app.playback=request;sourceIndex=0;episodeCount++;watchedSinceSync=0;externalSub="";addonSubtitles.clear();titleLabel.setText(title.name+" · S"+next.season+" E"+next.episode);prepare(app.resume(title.id,next.id));loadSubtitles();});}catch(Exception e){handler.post(()->{if(token==serial&&!isFinishing()){status.setText(StremioClient.readable(e));showControls(true);}});}});
    }
    private void sync(boolean ended){if(player==null||request==null)return;app.progress(request.title,request.videoId,player.getCurrentPosition(),player.getDuration(),watchedSinceSync,ended);watchedSinceSync=0;}
    private void showControls(boolean show){if(mini){controlsVisible=false;return;}if(dialogOpen&&!show)return;controlsVisible=show;controls.setVisibility(show?View.VISIBLE:View.INVISIBLE);top.setVisibility(show?View.VISIBLE:View.INVISIBLE);if(show){playButton.requestFocus();delayHide();}}
    private void delayHide(){handler.removeCallbacks(hide);if(isFinishing())return;if(!mini&&controlsVisible&&!dialogOpen&&player!=null&&player.isPlaying())handler.postDelayed(hide,6000);}
    public boolean dispatchKeyEvent(KeyEvent event){if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;int code=event.getKeyCode();if(dialogOpen)return false;
        if(code==KeyEvent.KEYCODE_P||code==KeyEvent.KEYCODE_WINDOW){if(mini)activity.restorePlayer();else activity.miniPlayer();return true;}
        if(code==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||code==KeyEvent.KEYCODE_MEDIA_PLAY||code==KeyEvent.KEYCODE_MEDIA_PAUSE){if(player!=null){player.setPlayWhenReady(code==KeyEvent.KEYCODE_MEDIA_PLAY||code==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE&&!player.getPlayWhenReady());showControls(true);}return true;}
        if(mini)return false;
        if(!controlsVisible){if(code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT){if(player!=null)player.seekTo(Math.max(0,player.getCurrentPosition()+(code==KeyEvent.KEYCODE_DPAD_LEFT?-10000:10000)));showControls(true);return true;}if(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_DPAD_UP||code==KeyEvent.KEYCODE_DPAD_DOWN||code==KeyEvent.KEYCODE_ENTER){showControls(true);return true;}}
        delayHide();return false;
    }
    private void show(GlassDialog dialog){
        if(isFinishing())return;handler.removeCallbacks(hide);dialogs.add(dialog);dialogOpen=true;
        dialog.setOnDismissListener(d->{dialogs.remove(dialog);dialogOpen=!dialogs.isEmpty();if(!isFinishing())delayHide();});dialog.show();
    }
    private void seekBy(long delta){if(player!=null)player.seekTo(Math.max(0,player.getCurrentPosition()+delta));}
    public void onBackPressed(){if(controlsVisible&&player!=null&&player.isPlaying())showControls(false);else finish();}
    public void onResume(){if(player==null)return;if(backgroundWasPlaying){backgroundWasPlaying=false;player.play();}if(player.getPlaybackState()==Player.STATE_BUFFERING){handler.removeCallbacks(watchdog);handler.postDelayed(watchdog,25000);}delayHide();}
    public void onStop(){if(player!=null){backgroundWasPlaying=player.getPlayWhenReady();sync(false);player.pause();lastSample=0;handler.removeCallbacks(hide);handler.removeCallbacks(watchdog);}}
    public void release(){if(closed)return;sync(false);closed=true;serial++;for(Dialog dialog:new ArrayList<>(dialogs))if(dialog.isShowing())dialog.dismiss();dialogs.clear();dialogOpen=false;handler.removeCallbacksAndMessages(null);if(player!=null){video.setPlayer(null);player.release();player=null;}updateResolution(VideoSize.UNKNOWN);root.setKeepScreenOn(false);app.playback=null;}
    private static String clock(long ms){long sec=Math.max(0,ms/1000);return sec>=3600?String.format(Locale.US,"%d:%02d:%02d",sec/3600,sec/60%60,sec%60):String.format(Locale.US,"%02d:%02d",sec/60,sec%60);}
}
