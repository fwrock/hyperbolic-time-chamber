#!/usr/bin/env python3
"""Physical metrics and frozen, fail-closed criteria for matched MICRO experiments."""
from collections import Counter
import json
import math
import statistics
import xml.etree.ElementTree as ET

from validate_sumo_micro import read_htc, trajectory, sumo_samples


def percentile(values, probability):
    values = sorted(values)
    if not values:
        return None
    rank = (len(values)-1) * probability
    low = math.floor(rank)
    return values[low] + (values[math.ceil(rank)]-values[low]) * (rank-low)


def traffic_metrics(root, comparison, protocol):
    canonical = comparison['canonical']
    rows = read_htc(root/'htc/reports')
    quantum = canonical.get('runtimeTickSeconds',1.0)
    htc = trajectory(rows,canonical)
    reference = sumo_samples(root/'sumo/fcd.xml',canonical)
    approach = canonical['links'][0]
    step = canonical.get('comparisonStepSeconds',1.0)
    horizon = canonical.get('horizonSeconds',180)
    keys = [round(i*step,9) for i in range(round(horizon/step)+1)]
    def queues(samples):
        counts=Counter(round(s['time'],9) for s in samples.values()
                       if s['link']==approach and s['speed']<0.1)
        return [counts[t] for t in keys]
    a,b=queues(htc),queues(reference)
    first_exit={}
    if len(canonical['links'])>1:
        first_exit={str(r['data']['car_id']): r['data']['tick']*quantum for r in rows
                    if r['event_type']=='leave_micro_link' and str(r['data']['link_id'])==approach}
    reference_exit={}
    downstream=canonical['links'][1] if len(canonical['links'])>1 else None
    for _,elem in ET.iterparse(root/'sumo/fcd.xml',events=('end',)):
        if elem.tag!='timestep': continue
        for vehicle in elem.findall('vehicle'):
            lane=vehicle.get('lane')
            link=canonical.get('sumoLaneMap',{}).get(lane,{}).get('linkId',lane.rsplit('_',1)[0])
            if link==downstream: reference_exit.setdefault(vehicle.get('id'),float(elem.get('time')))
        elem.clear()
    headways=[]
    order=sorted(reference_exit,key=lambda v:(reference_exit[v],v))
    for previous,vehicle in zip(order,order[1:]):
        if previous not in first_exit or vehicle not in first_exit:
            raise AssertionError('missing HTC discharge event')
        left,right=reference_exit[previous],reference_exit[vehicle]
        if canonical['signal']:
            # A frame ending on a phase boundary belongs to the preceding physics interval.
            left-=canonical.get('physicsStepSeconds',0.1)/2
            right-=canonical.get('physicsStepSeconds',0.1)/2
            if int(left//60)!=int(right//60) or left%60>=30 or right%60>=30: continue
        expected=reference_exit[vehicle]-reference_exit[previous]
        actual=first_exit[vehicle]-first_exit[previous]
        headways.append(dict(previousVehicle=previous,vehicle=vehicle,htcSeconds=actual,
                             sumoSeconds=expected,errorSeconds=actual-expected))
    errors=[v['durationDelta'] for v in comparison['vehicles']]
    absolute=[abs(v) for v in errors]
    htc_durations=[v['htcDuration'] for v in comparison['vehicles']]
    sumo_durations=[v['sumoDuration'] for v in comparison['vehicles']]
    def distribution(values):
        return dict(count=len(values),mean=statistics.mean(values),median=statistics.median(values),
                    p95=percentile(values,0.95),minimum=min(values),maximum=max(values))
    trips=ET.parse(root/'sumo/tripinfo.xml').getroot().findall('tripinfo')
    complete=[r for r in rows if r['event_type']=='sumo_tripinfo' and not r['data'].get('horizon_truncated',False)]
    n=len(canonical['vehicles'])
    coverage=min(len(complete),len(trips))/n
    throughput_a=len(complete)*3600/horizon
    throughput_b=len(trips)*3600/horizon
    throughput_error=abs(throughput_a-throughput_b)/throughput_b if throughput_b else None
    criteria=protocol['acceptanceCriteria']
    headway_mae=statistics.mean(abs(h['errorSeconds']) for h in headways) if headways else None
    queue_rmse=math.sqrt(statistics.mean((x-y)**2 for x,y in zip(a,b)))
    checks=dict(
        completionCoverage=coverage==criteria['requiredCompletionCoverage'],
        meanAbsoluteTripTime=statistics.mean(absolute)<=criteria['maxMeanAbsoluteTripTimeErrorSeconds'],
        p95AbsoluteTripTime=percentile(absolute,0.95)<=criteria['maxP95AbsoluteTripTimeErrorSeconds'],
        routeDistanceRmse=comparison['meanRouteDistanceRmseM']<=criteria['maxMeanRouteDistanceRmseM'],
        speedRmse=comparison['meanSpeedRmseMps']<=criteria['maxMeanSpeedRmseMps'],
        queueCountRmse=queue_rmse<=criteria['maxQueueCountRmseVehicles'],
        completedCohortThroughput=throughput_error is not None and throughput_error<=criteria['maxThroughputRelativeError'])
    if len(canonical['links'])>1 and n>1:
        checks['dischargeHeadway']=headway_mae is not None and headway_mae<=criteria['maxDischargeHeadwayMaeSeconds']
    return dict(passed=all(checks.values()),criteriaVersion=protocol['version'],checks=checks,
        completionCoverage=coverage,signedDurationBiasSeconds=statistics.mean(errors),
        meanAbsoluteDurationErrorSeconds=statistics.mean(absolute),p95AbsoluteDurationErrorSeconds=percentile(absolute,0.95),
        htcTripTimeDistributionSeconds=distribution(htc_durations),sumoTripTimeDistributionSeconds=distribution(sumo_durations),
        queue=dict(definition=criteria['queueDefinition'],sampleStepSeconds=step,samples=len(keys),
                   rmseVehicles=queue_rmse,htcMaximumVehicles=max(a),sumoMaximumVehicles=max(b),
                   htcVehicleSeconds=sum(a)*step,sumoVehicleSeconds=sum(b)*step),
        dischargeHeadways=dict(definition=criteria['headwayDefinition'],matchedPairs=len(headways),
            maeSeconds=headway_mae,pairs=headways),
        completedCohortThroughput=dict(observationSeconds=horizon,htcVehiclesPerHour=throughput_a,
            sumoVehiclesPerHour=throughput_b,relativeError=throughput_error,
            limitation='Cohort completion rate over the fixed horizon; does not estimate saturated capacity.'),
        routeDistanceRmseM=comparison['meanRouteDistanceRmseM'],speedRmseMps=comparison['meanSpeedRmseMps'])


def paired_seed_summary(records):
    by_seed={r['seed']:r for r in records}
    if len(by_seed)!=len(records) or len(records)!=5:
        raise ValueError('v1 inference requires five distinct independent demand seeds')
    differences=[r['metrics']['signedDurationBiasSeconds'] for r in records]
    mean=statistics.mean(differences)
    sd=statistics.stdev(differences)
    half=2.776445105*sd/math.sqrt(len(differences))
    return dict(independentSeeds=sorted(by_seed),sampleSize=len(differences),inferenceUnit='seeded demand realization',
        pairedMeanDurationDifferenceSeconds=mean,standardDeviationSeconds=sd,
        confidenceLevel=0.95,studentTDegreesOfFreedom=4,studentTCriticalValue=2.776445105,
        meanDifferenceCiSeconds=[mean-half,mean+half],
        interpretation='Exploratory Student-t interval assumes independent, approximately normal scenario-level differences. Conditional on randomized demand with deterministic drivers; excludes broader model and field-data uncertainty.')
