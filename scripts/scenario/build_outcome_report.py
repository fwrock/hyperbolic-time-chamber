#!/usr/bin/env python3
"""Create a focused, reproducible PDF assessment of HTC-SUMO traffic outcomes."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import statistics

REPO=Path(__file__).resolve().parents[2]
SOURCE=REPO/'docs/reports/htc-comparison-2026-10-27'


def read(path):return json.loads(Path(path).read_text())
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def write(path,value):Path(path).write_text(json.dumps(value,indent=2,allow_nan=False)+'\n')


def charts(study,out):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    import numpy as np
    from validate_sumo_micro import trajectory, sumo_samples
    out.mkdir(parents=True,exist_ok=True);folder=out/'figures';folder.mkdir(exist_ok=True)
    sci=read(SOURCE/'representative-scientific.json');physical=read(SOURCE/'representative-physical.json')
    protocol=read(REPO/'scripts/scenario/sumo_scientific_protocol_v2.json')
    historical=read(REPO/'docs/validation/micro-discharge-2026-10-25.json')
    plt.rcParams.update({'font.family':'DejaVu Sans','font.size':11,'axes.spines.top':False,
                         'axes.spines.right':False,'figure.facecolor':'white','savefig.facecolor':'white'})
    blue='#247ba0';gray='#333333';orange='#df8a32';saved=[]
    def save(fig,name):
        fig.tight_layout()
        for ext in ('png','svg'):fig.savefig(folder/(name+'.'+ext),dpi=200,bbox_inches='tight')
        plt.close(fig);saved.append(name)
    sig=sci['signal'];vs=physical['signal']['vehicles']
    fig,axes=plt.subplots(1,2,figsize=(9.5,3.3))
    ids=[v['vehicle'] for v in vs];x=np.arange(len(vs))
    for key,label,col,offset in [('htcDuration','HTC',blue,-.18),('sumoDuration','SUMO',gray,.18)]:
        axes[0].bar(x+offset,[v[key] for v in vs],width=.34,label=label,color=col)
    axes[0].set_xticks(x,ids,fontsize=8);axes[0].set_xlabel('Vehicle ID');axes[0].set_ylabel('Simulated travel time (s)');axes[0].legend(fontsize=9)
    axes[1].bar(x,[v['durationDelta'] for v in vs],color=blue)
    axes[1].axhline(0,color='black',lw=.7);axes[1].set_xticks(x,ids,fontsize=8)
    axes[1].set_ylim(-.012,.125);axes[1].set_xlabel('Vehicle ID');axes[1].set_ylabel('HTC minus SUMO travel time (s)')
    save(fig,'travel-time-comparison')
    c=protocol['acceptanceCriteria']
    gates=[('Mean absolute trip-time error',sig['meanAbsoluteDurationErrorSeconds'],c['maxMeanAbsoluteTripTimeErrorSeconds'],'s'),
           ('P95 absolute trip-time error',sig['p95AbsoluteDurationErrorSeconds'],c['maxP95AbsoluteTripTimeErrorSeconds'],'s'),
           ('Mean route-position RMSE',sig['routeDistanceRmseM'],c['maxMeanRouteDistanceRmseM'],'m'),
           ('Mean speed RMSE',sig['speedRmseMps'],c['maxMeanSpeedRmseMps'],'m/s'),
           ('Halted queue-count RMSE',sig['queue']['rmseVehicles'],c['maxQueueCountRmseVehicles'],'vehicles'),
           ('Discharge-headway MAE',sig['dischargeHeadways']['maeSeconds'],c['maxDischargeHeadwayMaeSeconds'],'s'),
           ('Completed-cohort rate error',sig['completedCohortThroughput']['relativeError'],c['maxThroughputRelativeError'],'fraction')]
    fig,ax=plt.subplots(figsize=(9.5,3.5));y=np.arange(len(gates))
    ax.barh(y,[g[1]/g[2] for g in gates],color=blue,height=.6)
    ax.axvline(1,color=orange,ls='--',label='Declared limit')
    for i,g in enumerate(gates):ax.text(g[1]/g[2]+.025,i,f'{g[1]:.3f} / {g[2]:g} {g[3]}',va='center',fontsize=9)
    ax.set_yticks(y,[g[0] for g in gates],fontsize=10);ax.invert_yaxis();ax.set_xlim(0,1.16)
    ax.set_xlabel('Measured error divided by declared limit');ax.legend(loc='lower right',fontsize=9)
    save(fig,'agreement-criteria')
    native={};reference={};canonicals={};selections={};stop_tables={}
    for case in ('following','signal'):
        root=study/'runs'/f'r1-{case}-discrete-event-1'
        rows=[json.loads(line) for p in (root/'htc/reports').rglob('*.jsonl') for line in p.read_text().splitlines() if line]
        canon=read(study/'inputs'/case/'canonical.json');canonicals[case]=canon
        native[case]=trajectory(rows,canon)
        reference[case]=sumo_samples(study/'runs'/f'r1-{case}-sumo/sumo/fcd.xml',canon)
        step=canon['comparisonStepSeconds'];approach=canon['links'][0]
        def stopped(samples):
            return {vid:sum(1 for (vehicle,t),v in samples.items() if vehicle==vid and v['link']==approach and v['speed']<.1)*step for vid in canon['vehicles']}
        a=stopped(native[case]);b=stopped(reference[case]);stop_tables[case]=dict(htc=a,sumo=b)
        selections[case]=(max(canon['vehicles'],key=lambda vid:(b[vid],-canon['vehicles'].index(vid))) if case=='signal' else canon['vehicles'][-1])
        assert abs(sum(a.values())-sci[case]['queue']['htcVehicleSeconds'])<1e-8
        assert abs(sum(b.values())-sci[case]['queue']['sumoVehicleSeconds'])<1e-8
    fig,axes=plt.subplots(2,2,figsize=(9.5,5.2))
    for col,case in enumerate(('following','signal')):
        vid=selections[case]
        for values,label,color,style in [(native[case],'HTC',blue,'-'),(reference[case],'SUMO',gray,'--')]:
            series=sorted((t,v) for (vehicle,t),v in values.items() if vehicle==vid)
            for row,key in [(0,'distance'),(1,'speed')]:axes[row,col].plot([t for t,v in series],[v[key] for t,v in series],label=label,color=color,ls=style,lw=1.5)
        axes[0,col].set_title(case.capitalize()+f' vehicle {vid}');axes[0,col].set_ylabel('Route position (m)');axes[0,col].legend(fontsize=8)
        axes[1,col].set_ylabel('Speed (m/s)');axes[1,col].set_xlabel('Simulation time (s)')
    save(fig,'position-speed-trajectories')
    fig,axes=plt.subplots(1,2,figsize=(9.5,3.4))
    ts=[round(i*.1,9) for i in range(1801)];canon=canonicals['signal']
    for samples,label,color,style in [(native['signal'],'HTC',blue,'-'),(reference['signal'],'SUMO',gray,'--')]:
        counts=Counter(t for (vid,t),v in samples.items() if v['link']==canon['links'][0] and v['speed']<.1)
        axes[0].step(ts,[counts[t] for t in ts],where='post',label=label,color=color,ls=style,lw=1.3)
    axes[0].set_xlabel('Simulation time (s)');axes[0].set_ylabel('Stopped approach vehicles');axes[0].legend(fontsize=9)
    for values,label,color,offset in [(stop_tables['signal']['htc'],'HTC',blue,-.18),(stop_tables['signal']['sumo'],'SUMO',gray,.18)]:
        axes[1].bar(x+offset,[values[vid] for vid in ids],width=.34,label=label,color=color)
    axes[1].set_xticks(x,ids,fontsize=8);axes[1].set_xlabel('Vehicle ID');axes[1].set_ylabel('Stopped approach time (s)');axes[1].legend(fontsize=9)
    save(fig,'queue-and-stopped-time')
    pairs=sig['dischargeHeadways']['pairs'];x2=np.arange(1,len(pairs)+1)
    fig,axes=plt.subplots(1,2,figsize=(9.5,3.3))
    for key,label,color,offset in [('htcSeconds','HTC',blue,-.18),('sumoSeconds','SUMO',gray,.18)]:
        axes[0].bar(x2+offset,[p[key] for p in pairs],width=.34,label=label,color=color)
    axes[0].set_xticks(x2,x2,fontsize=8);axes[0].set_xlabel('Matched discharge pair');axes[0].set_ylabel('Headway (s)');axes[0].legend(fontsize=9)
    for index,key in enumerate(('htcVehiclesPerHour','sumoVehiclesPerHour')):
        axes[1].bar(index,sig['completedCohortThroughput'][key],color=blue if index==0 else gray)
    axes[1].set_xticks([0,1],['HTC','SUMO']);axes[1].set_ylabel('Completed cohort rate\n(vehicles per hour)')
    axes[1].set_ylim(0,420)
    for i in range(2):axes[1].text(i,365,'18 of 18',ha='center',fontsize=10)
    save(fig,'discharge-and-completion')
    older=[]
    for stage in ('held-out','stochastic'):
        for r in historical['campaigns'][stage]['experiments']:
            if r['policy']=='discrete-event':older.append(dict(name=r['name'],stage=stage,metrics=r['metrics'],passed=r['passed']))
    assert len(older)==9 and all(r['passed'] for r in older)
    labels=['Left turn','Right turn','Signal right','Segmented route']+[f'Seed {s}' for s in (607,709,811,907,1009)]
    fig,axes=plt.subplots(1,2,figsize=(9.5,3.5));pos=np.arange(9)
    for ax,key,limit,label in [(axes[0],'meanAbsoluteDurationErrorSeconds',.5,'Trip-time MAE (s)'),(axes[1],'routeDistanceRmseM',2,'Mean route-position RMSE (m)')]:
        ax.bar(pos,[r['metrics'][key] for r in older],color=[blue]*4+[orange]*5)
        ax.axhline(limit,color=gray,ls='--',label='Declared limit');ax.set_ylabel(label)
        ax.set_xticks(pos,labels,rotation=40,ha='right',fontsize=8);ax.legend(fontsize=9)
    save(fig,'historical-v2-agreement')
    queue_delta=(sig['queue']['htcVehicleSeconds']/sig['queue']['sumoVehicleSeconds']-1)*100
    summary=dict(question='Do HTC and SUMO produce similar simulated traffic outcomes?',
        assessment='Strong numerical agreement within the declared tolerances for the tested matched single-lane scenarios.',
        representativeRun='First measured conservative one-JVM run for each case; no error-based selection',
        currentScientific=sci,currentPhysical=physical,gates=[dict(metric=g[0],observed=g[1],limit=g[2],unit=g[3],passed=g[1]<=g[2]) for g in gates],
        signalQueueVehicleTimeDifferencePercent=queue_delta,illustrativeVehicles=selections,
        stoppedApproachSeconds=stop_tables,historicalV2Cases=older,
        provenance=dict(campaignManifestSha256=read(SOURCE/'summary.json')['manifestSha256'],
            sourceSha256={str(p.relative_to(REPO)):sha(p) for p in [SOURCE/'representative-scientific.json',SOURCE/'representative-physical.json',SOURCE/'summary.json',REPO/'docs/validation/micro-discharge-2026-10-25.json',REPO/'scripts/scenario/sumo_scientific_protocol_v2.json']},
            figureSha256={name:{ext:sha(folder/(name+'.'+ext)) for ext in ('png','svg')} for name in saved}))
    write(out/'outcome-metrics.json',summary)


def pdf(out):
    from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, Image, PageBreak, KeepTogether
    from reportlab.lib import colors
    from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
    from reportlab.lib.enums import TA_LEFT
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    from reportlab.lib.utils import ImageReader
    from xml.sax.saxutils import escape
    fonts=Path('/usr/share/fonts/truetype/dejavu')
    for name,file in [('Body','DejaVuSerif.ttf'),('Bold','DejaVuSerif-Bold.ttf'),('Italic','DejaVuSerif.ttf')]:pdfmetrics.registerFont(TTFont(name,str(fonts/file)))
    pdfmetrics.registerFontFamily('Body',normal='Body',bold='Bold',italic='Italic',boldItalic='Bold')
    styles=getSampleStyleSheet()
    styles.add(ParagraphStyle(name='Text',fontName='Body',fontSize=10.4,leading=14.3,spaceAfter=9))
    styles.add(ParagraphStyle(name='ReportTitle',fontName='Bold',fontSize=22,leading=27,spaceAfter=14))
    styles.add(ParagraphStyle(name='Section',fontName='Bold',fontSize=16,leading=21,spaceAfter=12))
    styles.add(ParagraphStyle(name='Sub',fontName='Bold',fontSize=11,leading=15,spaceBefore=7,spaceAfter=6))
    styles.add(ParagraphStyle(name='Note',fontName='Italic',fontSize=8.6,leading=11.4,spaceAfter=10))
    styles.add(ParagraphStyle(name='Cell',fontName='Body',fontSize=8.8,leading=11.6))
    styles.add(ParagraphStyle(name='HeadCell',fontName='Bold',fontSize=8.8,leading=11.6,textColor=colors.white))
    story=[];d=read(out/'outcome-metrics.json');sig=d['currentScientific']['signal'];phys=d['currentPhysical']['signal']['vehicles']
    width=504
    def p(text,style='Text'):story.append(Paragraph(text,styles[style]))
    def page(title):story.extend([PageBreak(),Paragraph(title,styles['Section'])])
    def table(headers,rows,widths):
        data=[[Paragraph(escape(str(v)),styles['HeadCell']) for v in headers]]
        data += [[Paragraph(escape(str(v)),styles['Cell']) for v in row] for row in rows]
        t=Table(data,colWidths=widths,repeatRows=1,hAlign='LEFT')
        t.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),colors.HexColor('#263746')),
            ('ROWBACKGROUNDS',(0,1),(-1,-1),[colors.white,colors.HexColor('#f1f4f6')]),
            ('GRID',(0,0),(-1,-1),.4,colors.HexColor('#d9d9d9')),
            ('VALIGN',(0,0),(-1,-1),'MIDDLE'),('LEFTPADDING',(0,0),(-1,-1),7),('RIGHTPADDING',(0,0),(-1,-1),7),
            ('TOPPADDING',(0,0),(-1,-1),6),('BOTTOMPADDING',(0,0),(-1,-1),6)]))
        story.extend([t,Spacer(1,10)])
    figure=0
    def image(name,caption):
        nonlocal figure
        figure+=1
        file=out/'figures'/(name+'.png');iw,ih=ImageReader(str(file)).getSize()
        story.extend([Image(str(file),width=width,height=width*ih/iw),Spacer(1,6),Paragraph(f'Figure {figure}. '+caption,styles['Note'])])
    def fmt(v):return '0.000' if abs(v)<1e-12 else (f'{v:.3f}' if abs(v)>=.001 else '<0.001')
    p('Similarity of HTC and SUMO traffic outcomes','ReportTitle')
    p('Numerical assessment of travel times, trajectories, speeds, queues and discharge<br/>27 October 2026','Note')
    p('<b>Assessment: HTC and SUMO produce very similar traffic outcomes in the tested matched single-lane scenarios.</b> Free-flow and lightly loaded following results are effectively identical at the reported precision. The signal-controlled case also passes every declared agreement criterion, while retaining a measurable difference in stopped vehicle-time.')
    p('This report evaluates the traffic produced by the simulators. Physical seconds describe vehicle movement and journeys; they are not seconds spent executing the software. The analysis uses retained measurements and does not introduce a new simulation campaign.')
    table(['Signal-controlled outcome','HTC','SUMO'],[
        ['Mean travel time',f"{sig['htcTripTimeDistributionSeconds']['mean']:.3f} s",f"{sig['sumoTripTimeDistributionSeconds']['mean']:.3f} s"],
        ['Median travel time',f"{sig['htcTripTimeDistributionSeconds']['median']:.3f} s",f"{sig['sumoTripTimeDistributionSeconds']['median']:.3f} s"],
        ['Completed scheduled vehicles','18 of 18','18 of 18'],['Maximum stopped approach queue','8 vehicles','8 vehicles'],
        ['Total stopped approach vehicle-time',f"{sig['queue']['htcVehicleSeconds']:.1f} vehicle-seconds",f"{sig['queue']['sumoVehicleSeconds']:.1f} vehicle-seconds"]],[240,132,132])
    p(f"Mean travel time differs by {sig['signedDurationBiasSeconds']:.3f} s, or approximately {100*sig['signedDurationBiasSeconds']/sig['sumoTripTimeDistributionSeconds']['mean']:.2f}% of SUMO's mean. Route-position RMSE is {sig['routeDistanceRmseM']:.3f} m and speed RMSE is {sig['speedRmseMps']:.3f} m/s. HTC records {abs(d['signalQueueVehicleTimeDifferencePercent']):.1f}% less stopped vehicle-time, so the outputs are close rather than identical.")
    p('The supported conclusion is numerical agreement for these controlled scenarios. The same coefficients and matched physical inputs are used in both engines. This does not establish general network accuracy or agreement with real traffic.')
    p('Reading guide','Sub')
    p('Pages 2-6 explain the agreement criteria and current measured outcomes. Page 7 presents the retained broader v2 case evidence. Page 8 states the policy and validity limits. Page 9 lists every signal-case trip and the source records.')
    page('Experimental scope and agreement criteria')
    p('The current examples are the first measured conservative one-JVM runs of the free-flow, following and signal-right-turn fixtures. Selection follows the fixed repetition order, not prediction error. The following case has four vehicles at six-second departure spacing; it is a lightly loaded test. The signal case has eighteen vehicles and a finite right-turn connector, with a stopped queue of up to eight vehicles.')
    p('The matched inputs include routes, departures, vehicle length, speed limit, acceleration, braking, reaction time and minimum gap. Runtime and physics steps are 0.1 s; driver dynamics are deterministic. Comparison uses a shared physical clock and route coordinates, without fitted time shifts. All intended vehicles must complete.')
    image('agreement-criteria','Signal-case errors relative to the predeclared v2 limits. A value below one satisfies its limit. The limits are study-specific numerical targets, not universal traffic-accuracy standards.')
    table(['Criterion','Measured','Declared limit'],[[g['metric'],fmt(g['observed'])+' '+g['unit'],f"{g['limit']:g} "+g['unit']] for g in d['gates']],[270,117,117])
    p('All seven error checks pass, together with 100% completion coverage. Applying the existing limits to reused benchmark cases is a descriptive consistency check; it does not make these cases a new holdout study.','Note')
    page('Travel times agree vehicle by vehicle')
    image('travel-time-comparison','All eighteen signal-case travel times and their paired differences. Positive error means HTC arrives later after accounting for actual departure. Each bar is one vehicle, not an independent scenario replication.')
    p('The signal case has mean absolute travel-time error 0.061 s and p95 absolute error 0.100 s. Its signed mean bias is +0.061 s: HTC is slightly slower in simulated travel, rather than showing offsetting large early and late errors. The differences are small relative to journeys averaging approximately 41.2 s.')
    table(['Case','Cars','HTC mean s','SUMO mean s','Trip-time MAE s'],[
        [case.capitalize(),r['htcTripTimeDistributionSeconds']['count'],f"{r['htcTripTimeDistributionSeconds']['mean']:.3f}",f"{r['sumoTripTimeDistributionSeconds']['mean']:.3f}",fmt(r['meanAbsoluteDurationErrorSeconds'])] for case,r in d['currentScientific'].items()],[110,45,105,105,139])
    p('Free-flow and following journeys take 24.3 s in both engines, with differences below 0.001 s. The lightly loaded following fixture does not by itself establish behavior under dense following; the signal queue provides the more demanding interaction example.')
    p('Trip time is computed from explicit actual departure and terminal arrival records. A short overlapping trace is not accepted as evidence that a vehicle completed its journey. The detailed per-vehicle table appears on page 9.')
    page('Position and speed trajectories')
    image('position-speed-trajectories',f"Following vehicle {d['illustrativeVehicles']['following']} is the last scheduled departure. Signal vehicle {d['illustrativeVehicles']['signal']} has the longest stopped approach time in the SUMO trace (earliest schedule breaks ties). This descriptive selection is independent of prediction fit; aggregate metrics use every vehicle.")
    table(['Case','Mean route-position RMSE','Mean speed RMSE'],[[case.capitalize(),fmt(r['routeDistanceRmseM'])+' m',fmt(r['speedRmseMps'])+' m/s'] for case,r in d['currentScientific'].items()],[150,177,177])
    p('The traces compare continuous route distance through road links and the connector. Local positions from unrelated links are never compared directly. The signal example exposes braking, queue waiting and release, while the aggregate RMSE includes all eighteen vehicles at matched physical timestamps.')
    p('The signal speed RMSE is about 0.38 km/h. Close trace agreement supports the tested longitudinal and junction-boundary behavior. It does not exercise general lane changing, competing junction movements or arbitrary spillback networks.')
    page('Queue size and stopped time')
    image('queue-and-stopped-time','Approach queue count over the full horizon and each vehicle\'s stopped approach time. A stopped vehicle has speed below 0.1 m/s. Startup standstill samples are included under the same definition in both engines.')
    p('Both simulators reach a maximum stopped queue of eight vehicles. The queue-count RMSE is 0.191 vehicles over 1,801 equally spaced samples. Most of the 180-second horizon has an empty queue, so this full-horizon RMSE must be read alongside the busy-period curve and stopped-time totals.')
    p('HTC produces 106.2 stopped vehicle-seconds versus SUMO\'s 112.6, a difference of -6.4 vehicle-seconds (-5.7%). This is the clearest aggregate discrepancy in the illustrated case. Matching the queue maximum therefore does not imply identical waiting exposure. The discrepancy is retained and is not removed by time shifting or changing thresholds.')
    p('Vehicle-seconds sum the sampled time spent stopped by all vehicles on the approach. This quantity is distinct from total journey time and does not include every period of reduced speed. The per-vehicle bars show how differences are distributed rather than hiding them behind the same queue maximum.')
    page('Discharge and completed traffic')
    image('discharge-and-completion','Sixteen matched consecutive vehicle pairs discharged within the same green interval, and the fixed-horizon completed-cohort rate. Pairs spanning different green intervals are excluded from the headway metric by its frozen definition.')
    p('Discharge-headway mean absolute error is 0.019 s, compared with the declared 0.3 s limit. The visible differences are approximately one 0.1-second clock step for three matched pairs; the remaining pairs agree at the reported precision. This supports similar queue-release timing for the tested movement.')
    p('All eighteen vehicles complete in both simulators. Dividing those completions by the 180-second observation horizon gives 360 vehicles per hour in both. The result demonstrates matching completion coverage and cohort rate. It is not an estimate of saturated intersection or road capacity because the demand cohort is fixed and finite.')
    p('Completion alone would be a weak similarity criterion: two engines could finish the same number of trips with very different delays or queues. Here it is considered together with travel-time errors, speed and position trajectories, queue behavior and discharge headways.')
    page('Broader retained v2 agreement evidence')
    image('historical-v2-agreement','Historical frozen v2 results: four physical cases (blue) and five fresh demand seeds (orange). Only the conservative record is plotted for each case to avoid counting policy repetition as an independent scenario. These are retained results from the earlier executable.')
    rows=[]
    names=['Left turn','Right turn','Signal right turn','Segmented route']+[f'Demand seed {s}' for s in (607,709,811,907,1009)]
    for label,r in zip(names,d['historicalV2Cases']):
        m=r['metrics'];rows.append([label,fmt(m['meanAbsoluteDurationErrorSeconds']),fmt(m['routeDistanceRmseM']),fmt(m['speedRmseMps']),'Pass' if r['passed'] else 'Fail'])
    table(['Case','Time MAE s','Position RMSE m','Speed RMSE m/s','Criteria'],rows,[150,82,104,102,66])
    p('All nine retained cases pass their declared criteria with full completion. The four physical cases vary geometry; the five seeds vary demand while keeping deterministic driver dynamics. They broaden the agreement evidence beyond the three current illustrations, but remain bounded matched-model experiments.')
    p('The current signal fixture reuses one of these released cases. It is not counted as another independent scientific observation. Earlier v1 randomized-demand failures remain failures and development evidence; the later cross-link following correction was evaluated with the separately frozen v2 protocol.','Note')
    page('Assessment and boundaries of the conclusion')
    p('<b>The supported result is strong agreement between HTC and SUMO for the tested matched single-lane traffic scenarios.</b> The agreement covers actual journey duration, continuous route position, speed, halted queues, discharge headways and completed traffic. The signal experiment passes every declared numerical criterion and retains its 5.7% stopped-time discrepancy.')
    table(['Question','Assessment'],[
        ['Are the simulated outcomes similar?','Yes, within the declared numerical tolerances on the tested scenarios.'],
        ['Are the outputs identical to SUMO?','No. Small differences remain, especially aggregate stopped vehicle-time.'],
        ['Do conservative and optimistic HTC change the committed traffic result?','The measured comparisons preserve the same committed physical outputs across the tested policies and one/two-JVM layouts.'],
        ['Is this general real-world validation?','No. The field study remains inconclusive; matched simulator agreement is a separate question.'],
        ['Does matching completed traffic establish road capacity?','No. The reported rate describes the finite completed cohort over its fixed horizon.']],[220,284])
    p('The 2026-10-27 benchmark contains five execution repetitions for each traffic configuration. Repeated execution confirms reproducibility; it does not supply five new demand scenarios. The measured committed-output comparisons all match their designated conservative reference, after excluding only nondeterministic run identity, wall-clock timestamps and the summary CPU-duration field.')
    p('SUMO and HTC share intentionally matched physical parameters. That is useful for checking numerical behavior and boundary handling, but it narrows what the result can establish. General multi-lane traffic, competing movements, arbitrary network spillback and city-scale accuracy need separate evaluation.')
    p('The retained field v2 study predicts followers using observed head-leader motion. Eight following episodes and four queues pass subset comparisons, but the fixed targets are ten and five. Its overall decision remains not-established. No traffic coefficients or acceptance limits were changed to prepare this PDF.')
    page('Per vehicle results and evidence sources')
    table(['Vehicle','HTC travel s','SUMO travel s','Difference s','Position RMSE m'],[
        [v['vehicle'],f"{v['htcDuration']:.3f}",f"{v['sumoDuration']:.3f}",f"{(0.0 if abs(v['durationDelta'])<1e-12 else v['durationDelta']):+.3f}",f"{v['routeDistanceRmseM']:.3f}"] for v in phys],[65,108,108,100,123])
    p('Sources and reproducibility','Sub')
    p('Current outcomes: representative-scientific.json and representative-physical.json in docs/reports/htc-comparison-2026-10-27. Raw commands, scenarios, reports and observations are retained in docs/validation/comparison-2026-10-27/raw-evidence.zip. Historical v2: docs/validation/micro-discharge-2026-10-25.json. Acceptance definitions: scripts/scenario/sumo_scientific_protocol_v2.json.','Note')
    p('The accompanying outcome-metrics.json records exact values, chart hashes, illustrative vehicle selection and source hashes. Source script: scripts/scenario/build_outcome_report.py. Original benchmark manifest SHA256: '+d['provenance']['campaignManifestSha256'], 'Note')
    p('Official SUMO output definitions: <link href="https://sumo.dlr.de/docs/Simulation/Output/TripInfo.html" color="#247ba0">TripInfo</link> and <link href="https://sumo.dlr.de/docs/Simulation/Output/FCDOutput.html" color="#247ba0">FCDOutput</link>. Definitions are consistent with the prior report\'s retained source references.','Note')
    target=out/'htc-sumo-traffic-outcomes-2026-10-27.pdf'
    def footer(canvas,doc):
        canvas.setFont('Body',8);canvas.setFillColor(colors.HexColor('#555555'))
        canvas.drawString(54,28,'HTC and SUMO traffic outcomes')
        canvas.drawRightString(558,28,str(doc.page))
    doc=SimpleDocTemplate(str(target),pagesize=(612,792),rightMargin=54,leftMargin=54,topMargin=44,bottomMargin=44,
        title='Similarity of HTC and SUMO traffic outcomes',author='HTC Digital Twin Platform')
    doc.build(story,onFirstPage=footer,onLaterPages=footer)
    print(target)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--study',type=Path,default=Path('/tmp/htc-comparison-study-20261027'))
    p.add_argument('--output',type=Path,default=REPO/'docs/reports/htc-sumo-outcomes-2026-10-27')
    p.add_argument('--charts',action='store_true');p.add_argument('--pdf',action='store_true')
    a=p.parse_args();out=a.output.resolve()
    if a.charts:charts(a.study.resolve(),out)
    if a.pdf:pdf(out)
