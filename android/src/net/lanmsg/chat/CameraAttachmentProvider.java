package net.lanmsg.chat;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Grants the selected camera app temporary access to one app-private capture file. */
public final class CameraAttachmentProvider extends ContentProvider {
  @Override public boolean onCreate(){return true;}
  File file(Uri uri)throws FileNotFoundException {String name=uri.getLastPathSegment();if(name==null||!name.matches("camera-[0-9a-fA-F-]{36}\\.jpg"))throw new FileNotFoundException("Invalid camera target");File file=new File(getContext().getCacheDir(),name);try{if(!file.getCanonicalFile().getParentFile().equals(getContext().getCacheDir().getCanonicalFile()))throw new FileNotFoundException("Invalid camera target");}catch(IOException e){throw new FileNotFoundException("Invalid camera target");}return file;}
  @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {File file=file(uri);int flags=mode.contains("w")?ParcelFileDescriptor.MODE_CREATE|ParcelFileDescriptor.MODE_READ_WRITE|ParcelFileDescriptor.MODE_TRUNCATE:ParcelFileDescriptor.MODE_READ_ONLY;return ParcelFileDescriptor.open(file,flags);}
  @Override public String getType(Uri uri){return "image/jpeg";}
  @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){File file;try{file=file(uri);}catch(Exception e){return null;}String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor result=new MatrixCursor(columns,1);MatrixCursor.RowBuilder row=result.newRow();for(String column:columns){if(OpenableColumns.DISPLAY_NAME.equals(column))row.add(file.getName());else if(OpenableColumns.SIZE.equals(column))row.add(file.length());else row.add(null);}return result;}
  @Override public int delete(Uri uri,String selection,String[] args){try{return file(uri).delete()?1:0;}catch(Exception e){return 0;}}
  @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
  @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
