package dev.chyken.assembl_ar.workflow.phase;

import dev.chyken.assembl_ar.model.*;
import java.util.*;

/** Learner-facing phase totals over the unchanged legacy section checkpoints. */
public final class PhaseProgressSummary {
    private final Set<String> complete;
    private final int total;
    private PhaseProgressSummary(Set<String> complete,int total){this.complete=Collections.unmodifiableSet(complete);this.total=total;}
    public static PhaseProgressSummary from(ProjectProgress progress,PhaseProgress.Records records){
        Map<String,StepStatus> statuses=new HashMap<>();for(StepProgress s:progress.getSteps())statuses.put(s.getStepId(),s.getStatus());
        Set<String> done=new HashSet<>();
        for(AssemblyPhase phase:AssemblyPhaseCatalog.phases()){
            boolean all=!phase.getSections().isEmpty();
            for(int section:phase.getSections())all&=statuses.get(AssemblyPhaseCatalog.section(section).getId())==StepStatus.COMPLETED;
            // An old completion record for one section must not complete a newly
            // combined multi-section phase. Split-section records remain useful.
            if(all||phase.getSections().size()==1&&records.isPhaseComplete(phase.getId()))done.add(phase.getId());
        }
        return new PhaseProgressSummary(done,AssemblyPhaseCatalog.phases().size());
    }
    public int completed(){return complete.size();}
    public int total(){return total;}
    public int percent(){return total==0?0:Math.round(100f*completed()/total);}
    public boolean completed(String phase){return complete.contains(phase);}
}
