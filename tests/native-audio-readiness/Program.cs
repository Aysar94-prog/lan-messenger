if (args.Length != 1)
{
    Console.Error.WriteLine("usage: NativeAudioReadiness <dll>");
    return 2;
}
return ReadinessCheck.Run(args[0]);