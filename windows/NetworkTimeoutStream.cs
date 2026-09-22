namespace LanMessenger;

// Wraps a network stream so every individual chunk read/write gets its own inactivity timeout,
// instead of one deadline covering an entire (possibly ~1 GB) streamed transfer — a stalled
// connection is still caught quickly, while a slow-but-progressing one is tolerated indefinitely.
sealed class NetworkTimeoutStream(Stream inner,TimeSpan timeout) : Stream
{
    public override bool CanRead=>inner.CanRead;
    public override bool CanWrite=>inner.CanWrite;
    public override bool CanSeek=>false;
    public override long Length=>throw new NotSupportedException();
    public override long Position{get=>throw new NotSupportedException();set=>throw new NotSupportedException();}
    public override void Flush()=>inner.Flush();
    public override int Read(byte[] buffer,int offset,int count)=>throw new NotSupportedException();
    public override void Write(byte[] buffer,int offset,int count)=>throw new NotSupportedException();
    public override long Seek(long offset,SeekOrigin origin)=>throw new NotSupportedException();
    public override void SetLength(long value)=>throw new NotSupportedException();
    public override async ValueTask<int> ReadAsync(Memory<byte> buffer,CancellationToken cancellationToken=default)
    {
        using var timeoutSource=new CancellationTokenSource(timeout);
        using var linked=CancellationTokenSource.CreateLinkedTokenSource(cancellationToken,timeoutSource.Token);
        return await inner.ReadAsync(buffer,linked.Token);
    }
    public override async ValueTask WriteAsync(ReadOnlyMemory<byte> buffer,CancellationToken cancellationToken=default)
    {
        using var timeoutSource=new CancellationTokenSource(timeout);
        using var linked=CancellationTokenSource.CreateLinkedTokenSource(cancellationToken,timeoutSource.Token);
        await inner.WriteAsync(buffer,linked.Token);
    }
}
