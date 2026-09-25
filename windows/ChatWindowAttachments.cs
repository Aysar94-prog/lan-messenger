namespace LanMessenger;
sealed partial class ChatWindow
{
    Task AttachFile()=>PickAttachment(false);
    async Task PickAttachment(bool fast)
    {
        if(selected==null)return;var target=selected;
        using var dialog=new OpenFileDialog{Title=fast?"Fast file · keep the original available":$"Share a photo or file · up to {PeerEngine.MaxFileSize/1024/1024} MB",Filter="All files (*.*)|*.*|Images (*.png;*.jpg;*.jpeg;*.gif)|*.png;*.jpg;*.jpeg;*.gif"};
        if(dialog.ShowDialog(this)!=DialogResult.OK)return;
        try{
            var size=await Task.Run(()=>new FileInfo(dialog.FileName).Length);
            if(size>(fast?PeerEngine.MaxFastFileSize:PeerEngine.MaxFileSize))throw new IOException(fast?"Fast transfer limit is 1 TiB.":"Files must be 1 GiB or smaller.");
            if(selected!=target)return;
            pendingFast=fast;pendingAttachmentPath=dialog.FileName;pendingAttachmentName=Path.GetFileName(dialog.FileName);pendingAttachmentTarget=target;RenderPendingAttachment();composer.Focus();
        }catch(Exception e){MessageBox.Show(this,e.Message,"Could not prepare file");}
    }
    void RenderPendingAttachment()
    {
        foreach(Control control in attachmentDraft.Controls.Cast<Control>().ToArray())control.Dispose();attachmentDraft.Controls.Clear();
        if(pendingAttachmentPath==null){attachmentDraft.Visible=false;pendingAttachmentRow.Height=0;return;}
        attachmentDraft.Visible=true;pendingAttachmentRow.Height=150;
        var path=pendingAttachmentPath;long size;try{size=new FileInfo(path).Length;}catch{size=0;}
        // Only decode a thumbnail for attachments small enough that reading them fully is cheap;
        // a large file just shows its name/size, matching how most desktop apps handle big attachments.
        if(size>0&&size<=ThumbnailPreviewCap){Image? thumb=null;try{thumb=TryImageThumbnail(File.ReadAllBytes(path),150,125);}catch{}
            if(thumb!=null){var image=new PictureBox{Image=thumb,Width=150,Height=125,SizeMode=PictureBoxSizeMode.Zoom,Cursor=Cursors.Hand,BackColor=Color.FromArgb(220,226,237)};image.Click+=(_,_)=>PreviewImagePath(path,pendingAttachmentName);image.Disposed+=(_,_)=>thumb.Dispose();attachmentDraft.Controls.Add(image);}}
        var details=new FlowLayoutPanel{FlowDirection=FlowDirection.TopDown,WrapContents=false,AutoSize=true};
        details.Controls.Add(MessageLabel(pendingAttachmentName+"  ·  "+FormatSize(size)+(pendingFast?"\nFast transfer · unencrypted · keep original until downloaded":"\nReady to send"),10,Ink,330,true));
        var remove=new Button{Text="Remove",AutoSize=true};remove.Click+=(_,_)=>ClearPendingAttachment();details.Controls.Add(remove);StyleButtons(details);attachmentDraft.Controls.Add(details);
    }
    void ClearPendingAttachment(){pendingAttachmentPath=null;pendingAttachmentName="";pendingAttachmentTarget=null;RenderPendingAttachment();}
    Func<PeerEngine.Message,string?>? downloadDestinationPicker=null;
    Action<string>? openDownloadedFile=null;
    string? ChooseDownloadDestination(PeerEngine.Message message){
        if(downloadDestinationPicker!=null)return downloadDestinationPicker(message);
        using var dialog=new SaveFileDialog{FileName=message.FileName,Title="Download to",Filter="All files|*.*"};
        return dialog.ShowDialog(this)==DialogResult.OK?dialog.FileName:null;
    }
    async Task FileAction(PeerEngine.Message message)
    {
        try{
            if(engine.Downloading(message)){engine.CancelDownload(message);return;}
            var saved=engine.SavedDestination(message);
            if(saved.Length>0){if(openDownloadedFile!=null)openDownloadedFile(saved);else System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(saved){UseShellExecute=true});return;}
            if(engine.HasAttachment(message)&&PeerEngine.IsImageAttachment(message)){PreviewImage(message);return;}
            var destination=engine.PendingDestination(message);
            if(destination.Length==0){destination=ChooseDownloadDestination(message);if(string.IsNullOrEmpty(destination))return;}
            var download=Task.Run(()=>engine.DownloadToAsync(message,destination));await Task.Delay(50);Render();await download;
        }catch(OperationCanceledException){}catch(Exception ex){if(!IsDisposed)MessageBox.Show(this,ex.Message,"Download");}
        finally{if(!IsDisposed)Render();}
    }
    void SaveAttachment(){if(files.SelectedItem is FileItem item)SaveAttachment(item.Message);}
    void SaveAttachment(PeerEngine.Message message){using var dialog=new SaveFileDialog{FileName=message.FileName,Title="Save attachment",Filter="All files|*.*"};if(dialog.ShowDialog(this)!=DialogResult.OK)return;_=ExportAttachment(message,dialog.FileName);}
    // Streams straight to the destination file, decrypting on the fly — an export never needs
    // the whole attachment in memory either, same reasoning as the send/receive path.
    async Task ExportAttachment(PeerEngine.Message message,string destination){try{await Task.Run(()=>engine.ExportAttachmentAsync(message,destination));}catch(Exception e){if(!IsDisposed)MessageBox.Show(this,e.Message,"Could not save attachment");}}
    void PreviewImage(){if(files.SelectedItem is FileItem item)PreviewImage(item.Message);}
    void PreviewImage(PeerEngine.Message message){try{PreviewImageCore(engine.ReadAttachment(message),message.FileName);}catch{MessageBox.Show(this,"This attachment cannot be opened as an image. Use Save to export it.","Image preview");}}
    void PreviewImagePath(string path,string name){try{PreviewImageCore(File.ReadAllBytes(path),name);}catch{MessageBox.Show(this,"This file is not a supported image.","Image preview");}}
    void PreviewImageCore(byte[] data,string name){using var bytes=new MemoryStream(data);using var decoded=Image.FromStream(bytes);if((long)decoded.Width*decoded.Height>32000000)throw new IOException();using var picture=new Bitmap(decoded);using var window=new Form{Text=name,Size=new Size(900,700),StartPosition=FormStartPosition.CenterParent,BackColor=Color.FromArgb(20,24,31),KeyPreview=true};window.Controls.Add(new PictureBox{Dock=DockStyle.Fill,SizeMode=PictureBoxSizeMode.Zoom,Image=picture,BackColor=window.BackColor});window.KeyDown+=(_,e)=>{if(e.KeyCode==Keys.Escape)window.Close();};window.ShowDialog(this);}
}
