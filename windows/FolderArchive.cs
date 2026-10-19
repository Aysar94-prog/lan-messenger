using System.Diagnostics;
using System.IO.Compression;

namespace LanMessenger;

// A folder is an ordinary ZIP attachment: no new wire type or receiver-side extraction.
internal static class FolderArchive
{
    internal const int MaxDepth = 64;
    internal sealed record Progress(int Entries, long Bytes);
    sealed record Source(string Path, string Entry, bool Directory, long Length, DateTime Modified, string[]? Children);

    internal static FolderArchiveDraft Create(string sourceDirectory, string cacheRoot, long limit,
        CancellationToken cancellation = default, IProgress<Progress>? progress = null)
    {
        var root = Path.TrimEndingDirectorySeparator(Path.GetFullPath(sourceDirectory));
        cacheRoot = Path.GetFullPath(cacheRoot);
        if (IsWithin(cacheRoot, root)) throw new IOException("Choose a folder that does not contain LAN Messenger's temporary folder storage.");
        if ((File.GetAttributes(root) & FileAttributes.ReparsePoint) != 0)
            throw new IOException("Choose the original folder, rather than a link or junction.");
        var name = new DirectoryInfo(root).Name;
        if (string.IsNullOrEmpty(name) || root.Equals(Path.GetPathRoot(root), StringComparison.OrdinalIgnoreCase)) name = "Folder";
        ValidateName(name);
        cancellation.ThrowIfCancellationRequested();
        Directory.CreateDirectory(cacheRoot);
        CleanupAbandoned(cacheRoot);
        var work = Path.Combine(cacheRoot, Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(work);
        FolderArchiveDraft? draft = null;
        try
        {
            // Attachment metadata allows 120 characters. Preserve the ZIP extension when a
            // folder has a longer name; the original root name is still preserved inside it.
            var displayName = PeerEngine.SafeFileName(name);
            if(displayName.Length>116)displayName=displayName[..116];
            if(char.IsHighSurrogate(displayName[^1]))displayName=displayName[..^1];
            draft = new FolderArchiveDraft(work, displayName + ".zip");
            var sources = new List<Source>();
            long total = 0; int skipped = 0;
            var clock = Stopwatch.StartNew();
            void Report(int count, long bytes, bool force = false)
            {
                if (!force && clock.ElapsedMilliseconds < 150) return;
                progress?.Report(new(count, bytes)); clock.Restart();
            }
            void Visit(string path, string entry, int depth)
            {
                cancellation.ThrowIfCancellationRequested();
                var attributes = File.GetAttributes(path);
                if ((attributes & FileAttributes.ReparsePoint) != 0) { skipped++; return; }
                if (depth > MaxDepth) throw new IOException($"Folders can be at most {MaxDepth} levels deep.");
                if ((attributes & FileAttributes.Directory) != 0)
                {
                    var children = ReadChildren(path,cancellation);
                    sources.Add(new(path, entry + "/", true, 0, Directory.GetLastWriteTimeUtc(path), children));
                    Report(sources.Count, total);
                    foreach (var child in children)
                    {
                        var childName = Path.GetFileName(child); ValidateName(childName);
                        Visit(child, entry + "/" + childName, depth + 1);
                    }
                }
                else
                {
                    var info = new FileInfo(path);
                    total = checked(total + info.Length);
                    if (total > limit) throw new IOException("Folder contents exceed the selected transfer limit before packing.");
                    sources.Add(new(path, entry, false, info.Length, info.LastWriteTimeUtc, null));
                    Report(sources.Count, total);
                }
            }
            Visit(root, name, 0);
            cancellation.ThrowIfCancellationRequested();
            if (new DriveInfo(Path.GetPathRoot(work)!).AvailableFreeSpace < total + 1024 * 1024)
                throw new IOException("Not enough free space to prepare this folder.");
            long copied = 0;
            Report(sources.Count, 0, true);
            using (var file = new FileStream(draft.Path, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            using (var bounded = new LimitedOutput(file, limit, cancellation))
            using (var zip = new ZipArchive(bounded, ZipArchiveMode.Create, true))
            {
                var buffer = new byte[262144];
                int count = 0;
                foreach (var source in sources)
                {
                    cancellation.ThrowIfCancellationRequested(); CheckSource(source,cancellation);
                    var entry = zip.CreateEntry(source.Entry, CompressionLevel.Fastest);
                    if (!source.Directory)
                    {
                        using var input = new FileStream(source.Path, FileMode.Open, FileAccess.Read, FileShare.Read);
                        using var output = entry.Open();
                        long length = 0; int n;
                        while ((n = input.Read(buffer, 0, buffer.Length)) != 0)
                        {
                            cancellation.ThrowIfCancellationRequested();
                            length += n; copied += n;
                            if (length > source.Length) throw Changed();
                            output.Write(buffer, 0, n); Report(count, copied);
                        }
                        if (length != source.Length) throw Changed();
                        CheckSource(source,cancellation);
                    }
                    Report(++count, copied);
                }
            }
            // Catch changes to a previously packed file or directory while later files were read.
            foreach (var source in sources) { cancellation.ThrowIfCancellationRequested(); CheckSource(source,cancellation); }
            draft.SkippedLinks = skipped;
            Report(sources.Count, copied, true);
            cancellation.ThrowIfCancellationRequested();
            return draft;
        }
        catch
        {
            if (draft != null) draft.Dispose(); else Directory.Delete(work, false);
            throw;
        }
    }

    static bool IsWithin(string path, string parent) => path.Equals(parent, StringComparison.OrdinalIgnoreCase)
        || path.StartsWith(Path.EndsInDirectorySeparator(parent) ? parent : parent + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase);
    static IOException Changed() => new("The folder changed while being prepared. Close files being edited and choose the folder again.");
    static void ValidateName(string name)
    {
        if (string.IsNullOrEmpty(name) || name is "." or ".." || name.IndexOfAny(['/', '\\', ':', '\0']) >= 0)
            throw new IOException("The folder contains a name that cannot be safely stored in a ZIP.");
    }
    static string[] ReadChildren(string path,CancellationToken cancellation)
    {
        var children=new List<string>();
        foreach(var child in Directory.EnumerateFileSystemEntries(path)){cancellation.ThrowIfCancellationRequested();children.Add(child);}
        return children.ToArray();
    }
    static void CheckSource(Source source,CancellationToken cancellation)
    {
        var attributes = File.GetAttributes(source.Path);
        if ((attributes & FileAttributes.ReparsePoint) != 0 || ((attributes & FileAttributes.Directory) != 0) != source.Directory) throw Changed();
        if (source.Directory)
        {
            if (!ReadChildren(source.Path,cancellation).Order(StringComparer.Ordinal).SequenceEqual(source.Children!.Order(StringComparer.Ordinal))) throw Changed();
        }
        else
        {
            var info = new FileInfo(source.Path);
            if (info.Length != source.Length || info.LastWriteTimeUtc != source.Modified) throw Changed();
        }
    }

    // Each live archive holds an exclusive lock. Only known files from abandoned, old sessions
    // are removed; a second instance or a long-running preparation must never lose its source.
    internal static void CleanupAbandoned(string cacheRoot)
    {
        if (!Directory.Exists(cacheRoot)) return;
        foreach (var directory in Directory.EnumerateDirectories(cacheRoot))
        {
            try
            {
                if (!Guid.TryParseExact(System.IO.Path.GetFileName(directory), "N", out _) ||
                    (File.GetAttributes(directory) & FileAttributes.ReparsePoint) != 0 ||
                    Directory.GetLastWriteTimeUtc(directory) > DateTime.UtcNow.AddHours(-1)) continue;
                using (new FileStream(System.IO.Path.Combine(directory, "lease"), FileMode.Open, FileAccess.ReadWrite, FileShare.None))
                    File.Delete(System.IO.Path.Combine(directory, "archive.zip"));
                File.Delete(System.IO.Path.Combine(directory, "lease"));
                Directory.Delete(directory, false);
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    sealed class LimitedOutput(Stream inner, long limit, CancellationToken cancellation) : Stream
    {
        long written;
        public override void Write(byte[] buffer, int offset, int count)
        {
            cancellation.ThrowIfCancellationRequested();
            if (count > limit - written) throw new IOException("The folder ZIP exceeds the selected transfer limit.");
            inner.Write(buffer, offset, count); written += count;
        }
        public override bool CanRead => false;
        public override bool CanSeek => false;
        public override bool CanWrite => true;
        public override long Length => written;
        public override long Position { get => written; set => throw new NotSupportedException(); }
        public override void Flush() => inner.Flush();
        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
    }
}

// Keep the plaintext ZIP only while a draft or an active encrypted-store import owns it.
internal sealed class FolderArchiveDraft : IDisposable
{
    readonly string directory;
    readonly FileStream lease;
    readonly object gate = new();
    int owners = 1;
    bool disposed;
    internal string Path { get; }
    internal string Name { get; }
    internal int SkippedLinks { get; set; }
    internal FolderArchiveDraft(string directory, string name)
    {
        this.directory = directory; Name = name;
        Path = System.IO.Path.Combine(directory, "archive.zip");
        lease = new FileStream(System.IO.Path.Combine(directory, "lease"), FileMode.CreateNew, FileAccess.ReadWrite, FileShare.None);
    }
    internal IDisposable KeepAlive()
    {
        lock (gate) { ObjectDisposedException.ThrowIf(disposed, this); owners++; }
        return new Hold(this);
    }
    sealed class Hold(FolderArchiveDraft draft) : IDisposable
    {
        FolderArchiveDraft? owner = draft;
        public void Dispose() => Interlocked.Exchange(ref owner, null)?.Release();
    }
    public void Dispose()
    {
        lock (gate) { if (disposed) return; disposed = true; Release(); }
    }
    void Release()
    {
        lock (gate)
        {
            if (--owners != 0) return;
            lease.Dispose();
            try { File.Delete(Path); File.Delete(System.IO.Path.Combine(directory, "lease")); Directory.Delete(directory, false); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }
}
