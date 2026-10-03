package net.lanmsg.chat;

import android.app.AlertDialog;
import android.widget.*;
import java.util.*;

/** Select authenticated identities and their explicit LAN addresses. */
final class DirectConnectionUi {
  static void show(MainActivity a){
    PeerEngine e=a.engine();MessengerService service=a.host;if(e==null||service==null)return;
    LinearLayout panel=a.column();panel.setPadding(a.dp(18),a.dp(8),a.dp(18),a.dp(8));
    Switch enabled=new Switch(a);enabled.setText("Only connect to selected devices");enabled.setChecked(e.directOnly());panel.addView(enabled);
    panel.addView(a.label("Other contacts stay offline to this app. Discovery stops. Select verified devices and check their Wi-Fi IP addresses. Changing this setting ends active calls and interrupts transfers; queued messages are kept.",14));
    if(!e.directSettingsProblem.isEmpty())panel.addView(a.label(e.directSettingsProblem,14));
    LinkedHashMap<String,CheckBox> checks=new LinkedHashMap<>();LinkedHashMap<String,EditText> inputs=new LinkedHashMap<>();
    for(PeerEngine.Peer p:e.peers())if(p.trusted()){
      CheckBox check=new CheckBox(a);check.setText(p.name);String saved=e.directAddress(p.id);check.setChecked(!saved.isEmpty());panel.addView(check);
      EditText address=a.input("192.168.1.20",60);address.setSingleLine(true);address.setText(saved.isEmpty()?p.host+":"+p.port:saved);address.setContentDescription(p.name+" IP address and port");panel.addView(address);
      checks.put(p.id,check);inputs.put(p.id,address);
    }
    if(checks.isEmpty())panel.addView(a.label("Go online and verify a device first, then return here.",14));
    ScrollView scroll=new ScrollView(a);scroll.addView(panel);
    AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Direct connections").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
    dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
      LinkedHashMap<String,String> targets=new LinkedHashMap<>();
      try{for(String id:checks.keySet())if(checks.get(id).isChecked()){String address=inputs.get(id).getText().toString();PeerEngine.localAddress(address);targets.put(id,address);}
        if(enabled.isChecked()&&targets.isEmpty())throw new java.io.IOException("Select at least one verified device.");
      }catch(Exception invalid){Toast.makeText(a,invalid.getMessage(),Toast.LENGTH_LONG).show();return;}
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
      service.configureDirect(enabled.isChecked(),targets,result->{
        if(a.isDestroyed())return;
        if(result.isEmpty()){dialog.dismiss();a.lastSignature="";a.render();Toast.makeText(a,enabled.isChecked()?"Direct connections enabled":"Normal connections enabled",Toast.LENGTH_SHORT).show();}
        else{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);Toast.makeText(a,result,Toast.LENGTH_LONG).show();}
      });
    }));dialog.show();
  }
}
