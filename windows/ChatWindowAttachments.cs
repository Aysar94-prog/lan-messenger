namespace LanMessenger;
sealed partial class ChatWindow
{
    string folderDraftRoot = "";
    string lastAttachmentDirectory = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
    FolderArchiveDraft? pendingFolderArchive;
    CancellationTokenSource? folderPreparation;
    string folderPreparationText = "";
    // Optional combined-picker override for the native Windows integration harness.
    Func<bool,string?>? attachmentPicker=null;

    async Task PrepareFolder(string path,string target,bool fast)
    {
        if(selected!=target||!send.Enabled)return;
        ClearPendingAttachment();
        var cancellation=new CancellationTokenSource();folderPreparation=cancellation;
        pendingAttachmentTarget=target;folderPreparationText="Preparing folder ZIP…";
        RenderPendingAttachment();Render();
        var progress=new Progress<FolderArchive.Progress>(p=>{
            if(IsDisposed||folderPreparation!=cancellation)return;
            folderPreparationText=$"Preparing folder ZIP… {p.Entries:N0} entries · {FormatSize(p.Bytes)}";
            // Update just the label: recreating the Cancel button under the pointer loses clicks.
            if(attachmentDraft.Controls.Count>0)attachmentDraft.Controls[0].Text=folderPreparationText;
        });
        FolderArchiveDraft? archive=null;
        try{
            archive=await Task.Run(()=>FolderArchive.Create(path,folderDraftRoot,(fast?PeerEngine.MaxFastFileSize:PeerEngine.MaxFileSize),cancellation.Token,progress));
            if(IsDisposed||folderPreparation!=cancellation||selected!=target||cancellation.IsCancellationRequested)return;
            folderPreparation=null;
            pendingFolderArchive=archive;pendingAttachmentPath=archive.Path;pendingAttachmentName=archive.Name;
            pendingFast=fast;archive=null;
        }
        catch(OperationCanceledException){}
        catch(Exception error){if(!IsDisposed&&folderPreparation==cancellation)MessageBox.Show(this,error.Message,"Could not prepare folder");}
        finally{
            archive?.Dispose();
            if(folderPreparation==cancellation){folderPreparation=null;pendingAttachmentTarget=null;}
            cancellation.Dispose();
            if(!IsDisposed){RenderPendingAttachment();Render();}
        }
    }
    Task AttachFile()=>PickAttachment(false);
    async Task PickAttachment(bool fast)
    {
        if(selected==null||!send.Enabled)return;var target=selected;
        string? path;
        if(attachmentPicker!=null)path=attachmentPicker(fast);
        else{
            var initial=Directory.Exists(lastAttachmentDirectory)?lastAttachmentDirectory:Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            using var picker=new AttachmentPicker(fast?"Fast file · choose a file or folder · up to 1 TiB":"File · choose a file or folder · up to 1 GiB",initial);
            path=picker.ShowDialog(this)==DialogResult.OK?picker.SelectedPath:null;
        }
        if(path==null||selected!=target||!send.Enabled)return;
        try{
            if(Directory.Exists(path)){
                lastAttachmentDirectory=path;await PrepareFolder(path,target,fast);return;
            }
            var size=await Task.Run(()=>new FileInfo(path).Length);
            if(size>(fast?PeerEngine.MaxFastFileSize:PeerEngine.MaxFileSize))throw new IOException(fast?"Fast transfer limit is 1 TiB.":"Files must be 1 GiB or smaller.");
            if(selected!=target||!send.Enabled)return;
            ClearPendingAttachment();lastAttachmentDirectory=Path.GetDirectoryName(path)??lastAttachmentDirectory;
            pendingFast=fast;pendingAttachmentPath=path;pendingAttachmentName=Path.GetFileName(path);pendingAttachmentTarget=target;RenderPendingAttachment();composer.Focus();
        }catch(Exception e){if(!IsDisposed)MessageBox.Show(this,e.Message,"Could not prepare attachment");}
    }
    void RenderPendingAttachment()
    {
        foreach(Control control in attachmentDraft.Controls.Cast<Control>().ToArray())control.Dispose();attachmentDraft.Controls.Clear();
        if(folderPreparation!=null){
            attachmentDraft.Visible=true;pendingAttachmentRow.Height=90;
            attachmentDraft.Controls.Add(MessageLabel(folderPreparationText,10,Ink,330,true));
            var cancel=new Button{Text="Cancel",AutoSize=true};cancel.Click+=(_,_)=>{ClearPendingAttachment();Render();};
            StyleButton(cancel);attachmentDraft.Controls.Add(cancel);return;
        }
        if(pendingAttachmentPath==null){attachmentDraft.Visible=false;pendingAttachmentRow.Height=0;lastVoicePanel="";RenderVoicePanel();return;}
        attachmentDraft.Visible=true;pendingAttachmentRow.Height=150;
        var path=pendingAttachmentPath;long size;try{size=new FileInfo(path).Length;}catch{size=0;}
        // Only decode a thumbnail for attachments small enough that reading them fully is cheap;
        // a large file just shows its name/size, matching how most desktop apps handle big attachments.
        if(size>0&&size<=ThumbnailPreviewCap){Image? thumb=null;try{thumb=TryImageThumbnail(File.ReadAllBytes(path),150,125);}catch{}
            if(thumb!=null){var image=new PictureBox{Image=thumb,Width=150,Height=125,SizeMode=PictureBoxSizeMode.Zoom,Cursor=Cursors.Hand,BackColor=Color.FromArgb(220,226,237)};image.Click+=(_,_)=>PreviewImagePath(path,pendingAttachmentName);image.Disposed+=(_,_)=>thumb.Dispose();attachmentDraft.Controls.Add(image);}}
        var details=new FlowLayoutPanel{FlowDirection=FlowDirection.TopDown,WrapContents=false,AutoSize=true};
        var folderNote=pendingFolderArchive==null?"":"\nFolder ZIP · receiver saves and extracts"+(pendingFolderArchive.SkippedLinks>0?$"\n{pendingFolderArchive.SkippedLinks} links/junctions omitted":"");
        details.Controls.Add(MessageLabel(pendingAttachmentName+"  ·  "+FormatSize(size)+(pendingFast?(pendingFolderArchive==null?"\nFast transfer · unencrypted · keep original until downloaded":"\nFast transfer · unencrypted · ZIP kept for downloads"):"\nReady to send")+folderNote,10,Ink,330,true));
        var remove=new Button{Text="Remove",AutoSize=true};remove.Click+=(_,_)=>ClearPendingAttachment();details.Controls.Add(remove);StyleButtons(details);attachmentDraft.Controls.Add(details);
    }
    void ClearPendingAttachment(){folderPreparation?.Cancel();folderPreparation=null;pendingFolderArchive?.Dispose();pendingFolderArchive=null;pendingAttachmentPath=null;pendingAttachmentName="";pendingAttachmentTarget=null;pendingFast=false;if(!IsDisposed)RenderPendingAttachment();}
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
            if(!engine.Running){MessageBox.Show(this,"Go online to download this file.","Download");return;}
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
