#!/usr/bin/env python3
"""Render the retained numerical, demand-replication and field-pilot results."""
import argparse
import json
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence',type=Path,default=Path('docs/validation/micro-scientific-2026-10-25.json'))
    parser.add_argument('--output',type=Path,default=Path('docs/validation/figures/micro-scientific-2026-10-25'))
    args=parser.parse_args()
    evidence=json.loads(args.evidence.read_text())
    convergence=[e for e in evidence['campaigns']['convergence']['experiments'] if e['policy']=='discrete-event']
    seeds=[e for e in evidence['campaigns']['stochastic']['experiments'] if e['policy']=='discrete-event']
    inference=evidence['campaigns']['stochastic']['pairedSeedInference']
    field=evidence['fieldPilot']['results']
    plt.rcParams.update({'font.size':10,'axes.spines.top':False,'axes.spines.right':False,
                        'svg.fonttype':'none','svg.hashsalt':'htc-scientific-v1'})
    figure,axes=plt.subplots(1,3,figsize=(13.2,4.6))
    figure.subplots_adjust(left=0.06,right=0.98,bottom=0.25,top=0.78,wspace=0.43)
    figure.suptitle('MICRO timing, demand sensitivity and field pilot',x=0.06,ha='left',fontsize=16,fontweight='bold',y=0.98)
    figure.text(0.06,0.88,'Frozen protocol v1 · matched SUMO inputs · empirical pilot has a separate, exploratory scope',fontsize=10,color='#4b5563')
    biases=[e['metrics']['signedDurationBiasSeconds'] for e in convergence]
    axes[0].bar(range(4),biases,color=['#5479a6','#5479a6','#5479a6','#22836a'],width=0.6)
    axes[0].scatter([2,3],[0,0],color=['#5479a6','#22836a'],s=25,zorder=3)
    axes[0].set_xticks(range(4),['1.0 s','0.5 s','0.1 s','0.1 s\n+ carry'])
    axes[0].set_ylim(-0.4,10.2)
    axes[0].set_title('1.2 km numerical convergence',loc='left',fontsize=11,fontweight='bold')
    axes[0].set_ylabel('Trip-time bias (s)')
    axes[0].set_xlabel('Runtime tick; physics fixed at 0.1 s')
    for i,value in enumerate(biases):
        axes[0].text(i,max(0,value)+0.25,'<0.001' if abs(value)<0.001 else f'{value:.1f}',ha='center',fontsize=9)
    axes[0].grid(axis='y',alpha=0.2);axes[0].set_axisbelow(True)
    values=[e['metrics']['signedDurationBiasSeconds'] for e in seeds]
    mean=inference['pairedMeanDurationDifferenceSeconds'];lo,hi=inference['meanDifferenceCiSeconds']
    axes[1].scatter(range(5),values,color='#a94f48',s=35)
    axes[1].errorbar([5],[mean],yerr=[[mean-lo],[hi-mean]],fmt='D',color='#253349',capsize=5)
    axes[1].axhline(0,color='#687385',linestyle='--',linewidth=1)
    axes[1].set_xticks(range(6),[str(e['seed']) for e in seeds]+['Mean\n95% CI'])
    axes[1].set_ylim(-1.5,0.15)
    axes[1].set_title('Randomized-demand discrepancy',loc='left',fontsize=11,fontweight='bold')
    axes[1].set_ylabel('HTC − SUMO trip-time bias (s)')
    axes[1].set_xlabel('Independent demand seed (16 cars each)')
    axes[1].text(0.03,0.81,f'Mean {mean:.3f} s\nCI [{lo:.3f}, {hi:.3f}] s',transform=axes[1].transAxes,fontsize=9)
    axes[1].grid(axis='y',alpha=0.2);axes[1].set_axisbelow(True)
    errors=[e['positionRmseM'] for e in field]
    axes[2].bar(range(3),errors,color='#8b789b',width=0.6)
    axes[2].set_xticks(range(3),[str(e['subject']) for e in field])
    axes[2].set_ylim(0,9)
    axes[2].set_title('10 s field forecasts',loc='left',fontsize=11,fontweight='bold')
    axes[2].set_ylabel('Longitudinal-position RMSE (m)')
    axes[2].set_xlabel('NGSIM subject; 1 s initialization prefix')
    for i,value in enumerate(errors): axes[2].text(i,value+0.2,f'{value:.2f}',ha='center',fontsize=9)
    axes[2].grid(axis='y',alpha=0.2);axes[2].set_axisbelow(True)
    figure.text(0.06,0.075,'CI: exploratory Student-t interval over five scenario-level pairs; driver dynamics deterministic. Field pilot: initialized isolated cruising, not network validation.',fontsize=8)
    figure.text(0.06,0.035,'NGSIM observations: U.S. DOT/FHWA · doi.org/10.21949/1504477 · CC BY-SA 3.0. Figure licensed CC BY-SA 3.0.',fontsize=8,color='#4b5563')
    args.output.parent.mkdir(parents=True,exist_ok=True)
    figure.savefig(args.output.with_suffix('.png'),dpi=180)
    svg=args.output.with_suffix('.svg')
    figure.savefig(svg,metadata={'Date':evidence['date']})
    svg.write_text('\n'.join(line.rstrip() for line in svg.read_text().splitlines())+'\n')
    plt.close(figure)
    print(args.output.with_suffix('.png'))


if __name__=='__main__': main()
