"""Optional migration test using actual retained 0.3 harness builds as fixtures.
Args: java old-java-classes old-CsharpHarness.dll new-java-classes new-CsharpHarness.dll scratch
"""
import pathlib, sys, hashlib
java,old_java,old_cs,new_java,new_cs,scratch=sys.argv[1:]
sys.argv=[sys.argv[0],java,old_java,old_cs,scratch]
helpers=pathlib.Path(__file__).with_name('integration.py').read_text(encoding='utf-8').split('# Seed a real 0.2 snapshot')[0]
exec(helpers.replace('44972','46972').replace('44971','46971'))
try:
    a=Peer('java','Migration-Android','127.0.0.2');b=Peer('cs','Migration-Windows','127.0.0.3');aid,bid=a.id,b.id
    pair(a,b);code=a.command('CODE\t'+bid)[0][1]
    a.send(bid,'History from 0.3')
    wait_for(lambda:has(b,'History from 0.3','Received') and has(a,'History from 0.3','Delivered'),'Actual 0.3 engines seed verified encrypted history')
    b.stop();a.send(bid,'Pending from 0.3');a.stop()
    identity={k:(work/k/'identity.sec').read_bytes() for k in ['Migration-Android','Migration-Windows']}
    java_classes=new_java;dotnet_dll=new_cs
    a=Peer('java','Migration-Android','127.0.0.2');b=Peer('cs','Migration-Windows','127.0.0.3')
    assert a.id==aid and b.id==bid and a.command('CODE\t'+bid)[0][1]==code
    assert has(b,'History from 0.3','Received') and has(a,'History from 0.3','Delivered')
    wait_for(lambda:has(b,'Pending from 0.3','Received') and has(a,'Pending from 0.3','Delivered'),'0.4 preserves 0.3 identities, pins, history and queue without re-pairing')
    b.send(aid,'Reply after upgrade')
    wait_for(lambda:has(a,'Reply after upgrade','Received') and has(b,'Reply after upgrade','Delivered'),'Both migrated engines interoperate')
    for k,data in identity.items():assert (work/k/'identity.sec').read_bytes()==data
    print('ALL ACTUAL 0.3 TO 0.4 MIGRATION TESTS PASSED',flush=True)
finally:
    for p in processes:
        if p.poll() is None:p.kill()
    for p in processes:
        try:p.wait(timeout=5)
        except subprocess.TimeoutExpired:pass
