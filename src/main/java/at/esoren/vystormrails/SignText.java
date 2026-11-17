package at.esoren.vystormrails;
import net.kyori.adventure.text.Component;
import org.bukkit.block.sign.SignSide;
import org.bukkit.event.block.SignChangeEvent;
/** Keeps native Paper components and adapts Bukkit's legacy sign API. */
final class SignText {
 private SignText() {}
 static String[] plainLines(SignSide side) {
  String[] text=new String[4];
  for(int index=0;index<text.length;index++) {
   Component component;
   try {component=side.line(index);}
   catch(NoSuchMethodError absent) {
    String line=side.getLine(index);
    component=line==null?Component.empty():net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(line);
   }
   text[index]=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component==null?Component.empty():component);
  }
  return text;
 }
 static Component line(SignChangeEvent e,int index) {
  try{return e.line(index);}catch(NoSuchMethodError absent){
   String text=e.getLine(index);
   return text==null?null:net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(text);
  }
 }
 static void line(Vystorm.vystorm_core.compat.PlatformCompatInterface platform,SignChangeEvent e,int index,Component value) {
  try{e.line(index,value);}catch(NoSuchMethodError absent){e.setLine(index,platform.legacy(value));}
 }
 static void line(Vystorm.vystorm_core.compat.PlatformCompatInterface platform,SignSide side,int index,Component value) {
  try{side.line(index,value);}catch(NoSuchMethodError absent){side.setLine(index,platform.legacy(value));}
 }
}
