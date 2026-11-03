package com.lunarboreal.permissions;

import com.lunarboreal.api.command.LbrCommand;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import java.util.*;

/** Command parsing and shared structured mutations. All callers run on the server thread. */
final class PermissionCommands extends LbrCommand {
    private final PermissionsModule module;
    PermissionCommands(PermissionsModule module) {
        super("lperm", "lbr.permissions.admin", "permissions", "lperms"); this.module=module;
        description("管理权限组与玩家权限"); usage("/lperm group|user|list|reload");
    }
    @Override public void execute(CommandSender sender,String label,String[] args) {
        try {
            if(args.length==0) { reply(sender,"用法：/lperm list groups|nodes；group <组> create|delete|weight|parent|permission；user <UUID或已知名称> info|check|parent|permission；reload"); return; }
            if(args.length==1 && args[0].equals("reload")) { module.reloadPermissions(); reply(sender,"权限数据已重新加载"); return; }
            if(args.length==2 && args[0].equals("list")) {
                if(args[1].equals("groups")) reply(sender,String.join("、",new TreeSet<>(module.state().groups().keySet())));
                else if(args[1].equals("nodes")) { var nodes=module.knownNodes(); int limit=Math.min(nodes.size(),100); reply(sender,"已注册节点（"+nodes.size()+"项，显示前100项）："+String.join("、",nodes.subList(0,limit))+"；完整列表请在权限管理面板查看"); }
                else throw new IllegalArgumentException("列表类型须为groups或nodes"); return;
            }
            if(args.length>=3 && args[0].equals("user")) {
                UUID id=module.identify(args[1]);
                if(args.length==3 && args[2].equals("info")) { var u=module.state().users().get(id); reply(sender,"UUID："+id+"；默认组："+module.state().defaultGroup()+"；直接记录："+(u==null?"无（使用默认组）":u)); return; }
                if(args.length==4 && args[2].equals("check")) { reply(sender,module.check(id,PermissionState.node(args[3].toLowerCase(Locale.ROOT))).toString()); return; }
            }
            apply(module,args,sender.getName()); reply(sender,"权限设置已保存并更新");
        } catch(Exception ex) { reply(sender,"操作失败："+Objects.toString(ex.getMessage(),"请查看服务器日志")); }
    }
    private static void reply(CommandSender sender,String text) { sender.sendMessage(Component.text(text)); }
    static long expiry(String duration) {
        if(duration==null || duration.isBlank() || duration.equals("0") || duration.equals("permanent")) return 0;
        var match=java.util.regex.Pattern.compile("([1-9][0-9]{0,9})(s|min|m|h|d)?").matcher(duration);
        if(!match.matches()) throw new IllegalArgumentException("有效期须为正整数秒或带s/min/h/d后缀，0表示永久");
        long factor=switch(Objects.toString(match.group(2),"s")) {case "min","m"->60000L;case "h"->3600000L;case "d"->86400000L;default->1000L;};
        try { return Math.addExact(System.currentTimeMillis(),Math.multiplyExact(Long.parseLong(match.group(1)),factor)); }
        catch(ArithmeticException ex) { throw new IllegalArgumentException("有效期超出范围"); }
    }
    static boolean bool(String value) { if(value.equals("true"))return true;if(value.equals("false"))return false;throw new IllegalArgumentException("权限值须为true或false"); }
    static void apply(PermissionsModule m,String[] a,String actor) throws Exception {
        if(a.length<3 || !(a[0].equals("group")||a[0].equals("user")))throw new IllegalArgumentException("用法：group|user <对象> <操作>");
        boolean group=a[0].equals("group"); String target=a[1]; UUID uuid=group?null:m.identify(target);
        if(group) PermissionState.groupName(target);
        String action=a[2];
        if(group && (action.equals("create")||action.equals("delete"))) {
            arity(a,3,3); m.change(s->{ var groups=new HashMap<>(s.groups());
                if(action.equals("create")) { if(groups.containsKey(target))throw new IllegalArgumentException("权限组已存在");groups.put(target,new PermissionState.Group(target,0,Set.of(),Map.of())); }
                else { if(target.equals(s.defaultGroup()))throw new IllegalArgumentException("不能删除默认组");if(groups.remove(target)==null)throw new IllegalArgumentException("权限组不存在"); }
                return new PermissionState(groups,s.users(),s.defaultGroup()); },actor,String.join(" ",a)); return;
        }
        if(group && action.equals("weight")) { arity(a,4,4);int weight;try{weight=Integer.parseInt(a[3]);}catch(NumberFormatException ex){throw new IllegalArgumentException("权重须为整数");}
            m.change(s->{ var groups=new HashMap<>(s.groups());var old=requireGroup(s,target);groups.put(target,new PermissionState.Group(target,weight,old.parents(),old.permissions()));return new PermissionState(groups,s.users(),s.defaultGroup());},actor,String.join(" ",a));return; }
        if(!(action.equals("permission")||action.equals("parent")))throw new IllegalArgumentException("不支持的权限操作");
        boolean permission=action.equals("permission"); arity(a,5,permission?7:6);
        String op=a[3],key=permission?a[4].toLowerCase(Locale.ROOT):a[4];
        if(permission)PermissionState.node(key);else PermissionState.groupName(key);
        boolean add=permission?op.equals("set"):op.equals("add");
        if(!(add || (permission?op.equals("unset"):op.equals("remove"))))throw new IllegalArgumentException("权限使用set/unset，父组使用add/remove");
        if(permission && add)arity(a,6,7);else if(!add)arity(a,5,5);else arity(a,5,6);
        if(group && !permission && a.length!=5)throw new IllegalArgumentException("组继承不支持临时有效期");
        boolean value=permission && add && bool(a[5]);
        long expires=add?expiry(permission?(a.length==7?a[6]:null):(a.length==6?a[5]:null)):0;
        m.change(s->{
            var groups=new HashMap<>(s.groups());var users=new HashMap<>(s.users());
            if(!permission && !groups.containsKey(key))throw new IllegalArgumentException("父组不存在");
            if(group) {var old=requireGroup(s,target);var parents=new HashSet<>(old.parents());var grants=new HashMap<>(old.permissions());
                if(permission){if(add)grants.put(key,new PermissionState.Grant(value,expires));else grants.remove(key);}else{if(add)parents.add(key);else parents.remove(key);}
                groups.put(target,new PermissionState.Group(target,old.weight(),parents,grants));
            }else{var old=users.get(uuid);String name=old==null?(Bukkit.getPlayer(uuid)==null?"":Bukkit.getPlayer(uuid).getName()):old.name();
                var parents=old==null?new HashMap<String,PermissionState.Membership>():new HashMap<>(old.groups());var grants=old==null?new HashMap<String,PermissionState.Grant>():new HashMap<>(old.permissions());
                if(permission){if(add)grants.put(key,new PermissionState.Grant(value,expires));else grants.remove(key);}else{if(add)parents.put(key,new PermissionState.Membership(expires));else parents.remove(key);}
                users.put(uuid,new PermissionState.User(uuid,name,parents,grants));}
            return new PermissionState(groups,users,s.defaultGroup());
        },actor,String.join(" ",a));
    }
    private static PermissionState.Group requireGroup(PermissionState s,String group){var value=s.groups().get(group);if(value==null)throw new IllegalArgumentException("权限组不存在");return value;}
    private static void arity(String[] args,int min,int max){if(args.length<min||args.length>max)throw new IllegalArgumentException("参数数量不正确");}
    @Override public List<String> tabComplete(CommandSender sender,String alias,String[] a){
        if(a.length==1)return filter(List.of("group","user","list","reload"),a[0]);
        if(a.length==2){if(a[0].equals("group"))return filter(new ArrayList<>(module.state().groups().keySet()),a[1]);if(a[0].equals("list"))return filter(List.of("groups","nodes"),a[1]);if(a[0].equals("user"))return onlinePlayers(a[1]);}
        if(a.length==3)return filter(a[0].equals("group")?List.of("create","delete","weight","parent","permission"):List.of("info","check","parent","permission"),a[2]);
        if(a.length==4)return filter(a[2].equals("parent")?List.of("add","remove"):a[2].equals("permission")?List.of("set","unset"):List.of(),a[3]);
        if(a.length==5&&a[2].equals("parent"))return filter(new ArrayList<>(module.state().groups().keySet()),a[4]);
        if(a.length==6&&a[2].equals("permission")&&a[3].equals("set"))return filter(List.of("true","false"),a[5]);return List.of();
    }
}
