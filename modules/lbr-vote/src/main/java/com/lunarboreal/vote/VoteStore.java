package com.lunarboreal.vote;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Bounded UTF-8 state file; save completes before publishing state or applying effects. */
public final class VoteStore {
    public record Snapshot(VoteState vote,long cooldownUntil,String effect,long effectUntil,List<String> history){
        public Snapshot { history=List.copyOf(history); if(history.size()>50)throw new IllegalArgumentException("历史超限"); }
    }
    private final Path file;
    public VoteStore(Path file){this.file=file;}
    public Snapshot load() throws IOException {
        if(!Files.exists(file))return new Snapshot(null,0,"",0,List.of());
        if(Files.size(file)>131072)throw new IOException("投票状态文件过大");
        Properties p=new Properties();try(Reader r=Files.newBufferedReader(file)){p.load(r);}
        try {
            if(!"1".equals(p.getProperty("version")))throw new IllegalArgumentException("未知版本");
            VoteState v=null;
            if(p.containsKey("id")){
                Set<UUID> voters=new HashSet<>();for(String s:p.getProperty("electorate").split(","))voters.add(UUID.fromString(s));
                Map<UUID,Boolean> ballots=new HashMap<>();
                for(String k:p.stringPropertyNames())if(k.startsWith("ballot.")){
                    String raw=p.getProperty(k);if(!raw.equals("yes")&&!raw.equals("no"))throw new IllegalArgumentException("无效票");
                    ballots.put(UUID.fromString(k.substring(7)),raw.equals("yes"));
                }
                v=new VoteState(UUID.fromString(p.getProperty("id")),p.getProperty("proposal.effect"),Long.parseLong(p.getProperty("deadline")),Integer.parseInt(p.getProperty("seconds")),voters,ballots,Double.parseDouble(p.getProperty("quorum")),Double.parseDouble(p.getProperty("approval")));
            }
            List<String> history=new ArrayList<>();for(int i=0;i<50;i++){String h=p.getProperty("history."+i);if(h!=null)history.add(h);}
            return new Snapshot(v,Long.parseLong(p.getProperty("cooldown","0")),p.getProperty("effect",""),Long.parseLong(p.getProperty("effectUntil","0")),history);
        }catch(RuntimeException e){throw new IOException("投票状态损坏；拒绝覆盖",e);}
    }
    public void save(Snapshot state)throws IOException{
        Properties p=new Properties();p.setProperty("version","1");p.setProperty("cooldown",Long.toString(state.cooldownUntil));p.setProperty("effect",state.effect);p.setProperty("effectUntil",Long.toString(state.effectUntil));
        for(int i=0;i<state.history.size();i++)p.setProperty("history."+i,state.history.get(i));
        VoteState v=state.vote;if(v!=null){p.setProperty("id",v.id().toString());p.setProperty("proposal.effect",v.effect());p.setProperty("deadline",Long.toString(v.deadline()));p.setProperty("seconds",Integer.toString(v.effectSeconds()));p.setProperty("quorum",Double.toString(v.quorum()));p.setProperty("approval",Double.toString(v.approval()));p.setProperty("electorate",String.join(",",v.electorate().stream().map(UUID::toString).sorted().toList()));v.ballots().forEach((id,yes)->p.setProperty("ballot."+id,yes?"yes":"no"));}
        Files.createDirectories(file.toAbsolutePath().getParent());Path temp=Files.createTempFile(file.toAbsolutePath().getParent(),"vote-",".tmp");
        try{try(Writer w=Files.newBufferedWriter(temp)){p.store(w,"LC vote state v1");}Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
    }
}
