package net.lanmsg.chat;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.io.*;
import java.util.Locale;
import java.util.UUID;

/** Picking, preparing, previewing, downloading and exporting a chat attachment. */
final class AttachmentFlow {
  private AttachmentFlow(){}
  static void pickFastFile(MainActivity activity){if(activity.selected==null||activity.send==null||!activity.send.isEnabled())return;activity.attachmentTarget=activity.selected;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);try{activity.startActivityForResult(intent,46);}catch(Exception error){activity.problem(error);}}
  static void pickFile(MainActivity activity,boolean photo){PeerEngine e=MessengerService.engine;if(e==null||activity.selected==null)return;if(activity.send!=null&&!activity.send.isEnabled()){Toast.makeText(activity,"Verify this contact first.",Toast.LENGTH_LONG).show();return;}activity.attachmentTarget=activity.selected;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(photo?"image/*":"*/*");try{activity.startActivityForResult(intent,photo?41:42);}catch(Exception error){activity.problem(error);}}
  static void capturePhoto(MainActivity activity){if(activity.selected==null||activity.send==null||!activity.send.isEnabled()){Toast.makeText(activity,"Verify this contact first.",Toast.LENGTH_LONG).show();return;}try{activity.attachmentTarget=activity.selected;activity.cameraFile=new File(activity.getCacheDir(),"camera-"+UUID.randomUUID()+".jpg");activity.cameraUri=Uri.parse("content://"+activity.getPackageName()+".camera/capture/"+activity.cameraFile.getName());Intent intent=new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT,activity.cameraUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);intent.setClipData(android.content.ClipData.newRawUri("Camera photo",activity.cameraUri));activity.startActivityForResult(intent,44);}catch(Exception error){if(activity.cameraFile!=null)activity.cameraFile.delete();activity.problem(error);}}
  static void exportFile(MainActivity activity,PeerEngine.Message message){activity.exportMessage=message;try{activity.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,message.fileName),43);}catch(Exception error){activity.problem(error);}}
  static void fileAction(MainActivity activity,PeerEngine.Message message){
    PeerEngine e=MessengerService.engine;if(e==null)return;
    if(e.downloading(message)){e.cancelDownload(message);return;}
    String saved=e.savedDestination(message);
    if(!saved.isEmpty()){
      try{Uri uri=Uri.parse(saved);String type=activity.getContentResolver().getType(uri);
        int dot=message.fileName.lastIndexOf('.');if((type==null||type.equals("application/octet-stream"))&&dot>=0)type=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(message.fileName.substring(dot+1).toLowerCase(Locale.ROOT));
        Intent open=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,type==null?"application/octet-stream":type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        open.setClipData(ClipData.newRawUri(message.fileName,uri));activity.startActivity(Intent.createChooser(open,"Open file"));
      }catch(Exception failure){activity.problem(failure);}return;
    }
    if(e.hasAttachment(message)&&PeerEngine.isImageAttachment(message)){previewImage(activity,message);return;}
    String pending=e.pendingDestination(message);
    if(!pending.isEmpty()){startDirectDownload(activity,message,pending);return;}
    activity.exportMessage=message;
    try{activity.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,message.fileName).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),47);}catch(Exception failure){activity.problem(failure);}
  }
  static void startDirectDownload(MainActivity activity,PeerEngine.Message message,String reference){
    PeerEngine e=MessengerService.engine;if(e==null)return;
    new Thread(()->{activity.ui.postDelayed(()->activity.render(),100);try{e.downloadTo(message,reference);}catch(Exception failure){activity.ui.post(()->activity.problem(failure));}finally{activity.ui.post(()->{activity.lastSignature="";activity.render();});}},"lan-direct-download").start();
  }
  static byte[] readLimited(InputStream in)throws IOException{try(ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>PeerEngine.MAX_FILE_SIZE)throw new IOException("Files must be "+(PeerEngine.MAX_FILE_SIZE/1024/1024)+" MB or smaller.");out.write(buffer,0,n);}return out.toByteArray();}}
  static void prepareAttachment(MainActivity activity,String target,String name,Uri uri,long size,File cameraFileForCleanup){prepareAttachment(activity,target,name,uri,size,cameraFileForCleanup,false);}
  static void prepareAttachment(MainActivity activity,String target,String name,Uri uri,long size,File cameraFileForCleanup,boolean fast){activity.ui.post(()->{if(!target.equals(activity.selected)){if(cameraFileForCleanup!=null)cameraFileForCleanup.delete();return;}clearPendingAttachment(activity);activity.pendingFast=fast;activity.pendingAttachmentUri=uri;activity.pendingAttachmentSize=size;activity.pendingCameraFile=cameraFileForCleanup;activity.pendingAttachmentName=PeerEngine.safeFileName(name);activity.pendingAttachmentTarget=target;renderPendingAttachment(activity);if(activity.composer!=null)activity.composer.requestFocus();Toast.makeText(activity,"Ready to send",Toast.LENGTH_SHORT).show();});}
  // Fallback path only, for the rare picker that doesn't report a usable size (see above) —
  // small enough already (bounded by MAX_FILE_SIZE) that keeping it as bytes is fine; written to
  // a private cache file so the rest of the send pipeline can treat it like any other Uri source.
  static void prepareAttachmentBytes(MainActivity activity,String target,String name,byte[] bytes)throws IOException{
    File tmp=new File(activity.getCacheDir(),"attach-"+UUID.randomUUID());
    try(FileOutputStream out=new FileOutputStream(tmp)){out.write(bytes);}
    prepareAttachment(activity,target,name,Uri.fromFile(tmp),bytes.length,tmp);
  }
  static void renderPendingAttachment(MainActivity activity){
    if(activity.attachmentDraft==null)return;activity.releaseImages(activity.attachmentDraft);activity.attachmentDraft.removeAllViews();
    if(activity.pendingAttachmentUri==null)return;
    activity.attachmentDraft.setPadding(activity.dp(10),activity.dp(6),activity.dp(10),activity.dp(6));activity.attachmentDraft.setBackground(activity.bg(Color.rgb(235,240,250)));
    activity.attachmentDraft.addView(activity.label(activity.pendingAttachmentName+"  ·  "+MainActivity.formatSize(activity.pendingAttachmentSize)+(activity.pendingFast?"\nFast transfer · unencrypted · keep original until downloaded":"\nReady to send"),14));
    Button remove=activity.button("Remove");remove.setOnClickListener(v->clearPendingAttachment(activity));activity.attachmentDraft.addView(remove);
    // Decoding even a small thumbnail is still blocking I/O — off the UI thread, then posted back
    // only if this is still the pending attachment (the user may have picked something else, or
    // sent/cleared it, by the time the decode finishes).
    if(activity.pendingAttachmentSize>0&&activity.pendingAttachmentSize<=MainActivity.THUMBNAIL_PREVIEW_CAP){
      final Uri uri=activity.pendingAttachmentUri;final String forName=activity.pendingAttachmentName;
      new Thread(()->{
        byte[] bytes=null;try(InputStream in=activity.getContentResolver().openInputStream(uri)){if(in!=null)bytes=readLimited(in);}catch(Exception ignored){}
        Bitmap bitmap=bytes!=null?activity.inlineBitmap(bytes):null;
        if(bitmap==null)return;
        final byte[] previewBytes=bytes;
        activity.ui.post(()->{
          if(uri!=activity.pendingAttachmentUri||activity.attachmentDraft==null){bitmap.recycle();return;}
          ImageView image=new ImageView(activity);image.setImageBitmap(bitmap);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setLayoutParams(new LinearLayout.LayoutParams(-1,activity.dp(170)));image.setBackground(activity.bg(Color.rgb(220,226,237)));image.setClipToOutline(true);image.setContentDescription("Preview "+forName);image.setOnClickListener(v->previewBytes(activity,previewBytes,forName));activity.attachmentDraft.addView(image,0);
        });
      },"lan-draft-thumbnail").start();
    }
  }
  static void clearPendingAttachment(MainActivity activity){if(activity.pendingCameraFile!=null){activity.pendingCameraFile.delete();activity.pendingCameraFile=null;}activity.pendingAttachmentUri=null;activity.pendingAttachmentSize=0;activity.pendingAttachmentName="";activity.pendingAttachmentTarget=null;if(activity.attachmentDraft!=null){activity.releaseImages(activity.attachmentDraft);activity.attachmentDraft.removeAllViews();}}
  static void previewImage(MainActivity activity,PeerEngine.Message message){PeerEngine e=MessengerService.engine;if(e==null)return;new Thread(()->{try{byte[] bytes=e.readAttachment(message);BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(options.outWidth<=0||options.outHeight<=0)throw new IOException("Not a supported image. Use Save file.");options.inSampleSize=1;while(options.outWidth/options.inSampleSize>1600||options.outHeight/options.inSampleSize>1600)options.inSampleSize*=2;options.inJustDecodeBounds=false;final Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(bitmap==null)throw new IOException("Cannot preview image");activity.ui.post(()->{if(activity.isFinishing()||activity.isDestroyed()){bitmap.recycle();return;}ImageView image=new ImageView(activity);image.setImageBitmap(bitmap);image.setAdjustViewBounds(true);image.setMaxHeight(activity.dp(500));AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(message.fileName).setView(image).setPositiveButton("Close",null).create();dialog.setOnDismissListener(d->{image.setImageDrawable(null);bitmap.recycle();});dialog.show();});}catch(Exception error){activity.ui.post(()->activity.problem(error));}},"lan-image-preview").start();}
  static void previewBytes(MainActivity activity,byte[] bytes,String name){new Thread(()->{try{BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(options.outWidth<=0||options.outHeight<=0)throw new IOException("This file is not a supported image.");options.inSampleSize=1;while(options.outWidth/options.inSampleSize>1600||options.outHeight/options.inSampleSize>1600)options.inSampleSize*=2;options.inJustDecodeBounds=false;final Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(bitmap==null)throw new IOException("Cannot preview image");activity.ui.post(()->{ImageView image=new ImageView(activity);image.setImageBitmap(bitmap);image.setAdjustViewBounds(true);image.setMaxHeight(activity.dp(500));AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(name).setView(image).setPositiveButton("Close",null).create();dialog.setOnDismissListener(d->{image.setImageDrawable(null);bitmap.recycle();});dialog.show();});}catch(Exception error){activity.ui.post(()->activity.problem(error));}},"lan-draft-preview").start();}
}
