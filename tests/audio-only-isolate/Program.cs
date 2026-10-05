using System;
class Program { static int Main(string[] args){ if(args.Length==0){Console.WriteLine("need dll");return 1;} return AudioOnly.Run(args[0]); } }
