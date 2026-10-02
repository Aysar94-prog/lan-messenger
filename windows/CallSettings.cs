namespace LanMessenger;

// "Allow incoming calls" — persisted per device, independent of the Online/Offline network
// preference, mirroring Android's CallSettings.java. Default enabled. Separate small plain-text
// atomic-write file, same pattern Program.cs already uses for network-preference.txt — not part
// of PeerEngine's encrypted state.txt, since this is a local-only UI preference, not account data.
public sealed class CallSettings
{
    readonly string path;
    readonly object gate = new();
    volatile bool allowIncomingCalls = true;

    public CallSettings(string dataDirectory)
    {
        path = Path.Combine(dataDirectory, "call-settings.txt");
        Load();
    }

    public bool AllowIncomingCalls
    {
        get => allowIncomingCalls;
        set
        {
            lock (gate)
            {
                allowIncomingCalls = value;
                try
                {
                    var tempPath = path + ".tmp";
                    File.WriteAllText(tempPath, value ? "1" : "0");
                    File.Move(tempPath, path, true);
                }
                catch { /* keep the in-memory choice for this session; recoverable save error */ }
            }
        }
    }

    void Load()
    {
        try
        {
            if (File.Exists(path))
            {
                var text = File.ReadAllText(path).Trim();
                allowIncomingCalls = text != "0";
            }
        }
        catch { allowIncomingCalls = true; } // corrupt/unreadable settings: disable-safe default stays enabled per plan, admission still checked live
    }
}
