package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.net.Uri;
import android.view.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import java.util.*;
import dev.chyken.assembl_ar.R;
import dev.chyken.assembl_ar.camera.Phase2AssetCatalog;

/** Offline playlist. Captions follow actual media transitions, including replays and seeks. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class Phase2VideoPlayer implements AutoCloseable {
    public interface Listener{
        void onClip(int number);
        void onPlayback(int number,boolean playing,boolean seek);
        void onClipEnded();
        void onFinished();void onError(String message);
    }
    private final ExoPlayer player;
    private final PlayerView view;
    private float downX,downY;
    private long startPosition;
    private long dragDuration;
    private int dragClip;
    private boolean wasPlaying;
    private boolean dragging;
    private boolean released;
    private boolean seekPending;
    private boolean waitingAtBoundary,boundaryNotificationPending,playlistFinished;
    private final int clipCount;
    private final Listener listener;
    public Phase2VideoPlayer(Context context,List<String> assets,Listener listener){
        this.listener=listener;clipCount=assets.size();
        view=(PlayerView)LayoutInflater.from(context).inflate(R.layout.view_phase2_video,null,false);
        // Autoplay and narration holds must not cover short clips with transport controls.
        // The surface consumes touch gestures, so reveal controls explicitly on a user tap.
        view.setControllerAutoShow(false);
        view.hideController();
        player=new ExoPlayer.Builder(context).build();view.setPlayer(player);
        player.setPauseAtEndOfMediaItems(true);
        List<MediaItem> items=new ArrayList<>();
        for(String asset:assets)items.add(MediaItem.fromUri(new Uri.Builder().scheme("asset").path("/"+asset).build()));
        player.addListener(new Player.Listener(){
            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition,Player.PositionInfo newPosition,int reason){
                if(reason==Player.DISCONTINUITY_REASON_SEEK){seekPending=true;waitingAtBoundary=false;boundaryNotificationPending=false;playlistFinished=false;}
            }
            @Override public void onPlayWhenReadyChanged(boolean ready,int reason){
                if(reason==Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM){waitingAtBoundary=true;boundaryNotificationPending=true;}
                else if(ready){
                    waitingAtBoundary=false;boundaryNotificationPending=false;
                    if(playlistFinished){playlistFinished=false;player.seekTo(0,0);}
                }
            }
            @Override public void onEvents(Player source,Player.Events events){
                if(released)return;
                if(waitingAtBoundary){
                    if(boundaryNotificationPending){boundaryNotificationPending=false;listener.onClipEnded();}
                    return;
                }
                int index=source.getCurrentMediaItemIndex();
                int role=index>=0&&index<assets.size()?Phase2AssetCatalog.clipOrder(assets.get(index)):-1;
                boolean seek=seekPending;seekPending=false;
                listener.onPlayback(role,source.isPlaying(),seek);
            }
            @Override public void onMediaItemTransition(MediaItem item,int reason){
                if(!released&&item!=null)listener.onClip(Phase2AssetCatalog.clipOrder(assets.get(player.getCurrentMediaItemIndex())));
            }
            @Override public void onPlaybackStateChanged(int state){
                if(!released&&state==Player.STATE_ENDED&&!waitingAtBoundary){waitingAtBoundary=true;boundaryNotificationPending=true;}
            }
            @Override public void onPlayerError(PlaybackException error){if(!released)listener.onError("Video could not play. Retry the demo.");}
        });
        player.setMediaItems(items);player.prepare();
        View surface=view.getVideoSurfaceView();
        if(surface!=null)surface.setOnTouchListener((v,event)->{
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:downX=event.getX();downY=event.getY();startPosition=player.getCurrentPosition();dragDuration=player.getDuration();dragClip=player.getCurrentMediaItemIndex();wasPlaying=player.getPlayWhenReady();dragging=false;return true;
                case MotionEvent.ACTION_MOVE:
                    if(!dragging&&Math.abs(event.getX()-downX)>UiUtils.dp(context,12)&&Math.abs(event.getX()-downX)>Math.abs(event.getY()-downY)){dragging=true;player.pause();}
                    if(dragging&&dragDuration>0)player.seekTo(dragClip,Math.max(0,Math.min(dragDuration,startPosition+(long)((event.getX()-downX)/Math.max(1,v.getWidth())*dragDuration))));
                    return true;
                case MotionEvent.ACTION_UP:
                    if(dragging){if(wasPlaying)play();}
                    else{if(player.getPlayWhenReady())pause();else play();view.showController();}
                    v.performClick();return true;
                case MotionEvent.ACTION_CANCEL:return true;
                default:return true;
            }
        });
    }
    public View view(){return view;}
    public void play(){
        if(waitingAtBoundary){continueAfterNarration();return;}
        if(playlistFinished||player.getPlaybackState()==Player.STATE_ENDED){playlistFinished=false;player.seekTo(0,0);}
        player.play();
    }
    /** The completed frame stays visible until its spoken instruction has finished. */
    public void continueAfterNarration(){
        if(released||!waitingAtBoundary)return;
        waitingAtBoundary=false;boundaryNotificationPending=false;
        int index=player.getCurrentMediaItemIndex();
        if(index+1<clipCount){player.seekTo(index+1,0);player.play();}
        else {playlistFinished=true;listener.onFinished();}
    }
    public void pause(){waitingAtBoundary=false;boundaryNotificationPending=false;player.pause();}
    public long position(){return player.getCurrentPosition();}
    public int clip(){return player.getCurrentMediaItemIndex();}
    public void seek(int clip,long position){player.seekTo(clip,position);}
    @Override public void close(){released=true;view.setPlayer(null);player.release();}
}
