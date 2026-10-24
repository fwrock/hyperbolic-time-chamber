#!/usr/bin/env python3
"""Plot the retained discharge diagnostic and independently frozen v2 demand results."""
import argparse
import json
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence',type=Path,default=Path('docs/validation/micro-discharge-2026-10-25.json'))
    parser.add_argument('--output',type=Path,default=Path('docs/validation/figures/micro-discharge-2026-10-25'))
    args=parser.parse_args()
    evidence=json.loads(args.evidence.read_text())
    plt.rcParams.update({'font.size':10,'axes.spines.top':False,'axes.spines.right':False,
                        'svg.fonttype':'none','svg.hashsalt':'htc-discharge-v2'})
    fig,axes=plt.subplots(1,2,figsize=(11.5,4.6))
    fig.subplots_adjust(left=0.075,right=0.98,bottom=0.24,top=0.77,wspace=0.35)
    fig.suptitle('Cross-link following: correction and fresh validation',x=0.075,ha='left',fontsize=16,fontweight='bold',y=0.98)
    fig.text(0.075,0.88,'Matched SUMO inputs · 0.1-second physics · unchanged provisional equivalence criteria',color='#4b5563')
    for name,label,color,style in [('baseline','Original model','#a94f48','-'),
                                    ('stop-gap-only','Stop gap only','#8c7b50','--'),
                                    ('combined','Cross-link following + stop gap','#22836a','-')]:
        vehicles=evidence['development'][name]['vehicles']
        axes[0].plot(range(1,len(vehicles)+1),[v['durationDelta'] for v in vehicles],
                     label=label,color=color,linestyle=style,marker='o',markersize=3)
    axes[0].axhline(0,color='#687385',linewidth=0.8)
    axes[0].set_title('Development diagnostic: seed 101',loc='left',fontsize=11,fontweight='bold')
    axes[0].set_xlabel('Vehicle in departure order (16 cars)')
    axes[0].set_ylabel('HTC − SUMO trip duration (s)')
    axes[0].legend(fontsize=8,loc='lower left',frameon=False)
    axes[0].set_xticks([1,4,8,12,16])
    campaign=evidence['campaigns']['stochastic']
    runs=[e for e in campaign['experiments'] if e['policy']=='discrete-event']
    inference=campaign['pairedSeedInference']
    values=[e['metrics']['signedDurationBiasSeconds'] for e in runs]
    mean=inference['pairedMeanDurationDifferenceSeconds'];lo,hi=inference['meanDifferenceCiSeconds']
    axes[1].scatter(range(5),values,color='#22836a',s=35)
    axes[1].errorbar([5],[mean],yerr=[[mean-lo],[hi-mean]],fmt='D',color='#253349',capsize=5)
    axes[1].axhline(0,color='#687385',linestyle='--',linewidth=1)
    axes[1].set_xticks(range(6),[str(e['seed']) for e in runs]+['Mean\n95% CI'])
    axes[1].set_title('Fresh frozen v2 demand realizations',loc='left',fontsize=11,fontweight='bold')
    axes[1].set_xlabel('Independent demand seed (18 cars each)')
    axes[1].set_ylabel('Scenario mean duration bias (s)')
    axes[1].set_ylim(-0.01,max(0.12,hi*1.35,max(values)*1.35))
    axes[1].text(0.04,0.92,f'Mean {mean:.3f} s · CI [{lo:.3f}, {hi:.3f}] s',
                 transform=axes[1].transAxes,fontsize=9)
    for ax in axes:
        ax.grid(axis='y',alpha=0.2);ax.set_axisbelow(True)
    fig.text(0.075,0.085,'Left: paired diagnostic on previously inspected demand. Right: fresh cases frozen before model revision; deterministic drivers, randomized demand.',fontsize=8)
    fig.text(0.075,0.045,'The exploratory Student-t interval covers scenario-level demand uncertainty; these controlled comparisons do not establish empirical network validity.',fontsize=8,color='#4b5563')
    args.output.parent.mkdir(parents=True,exist_ok=True)
    fig.savefig(args.output.with_suffix('.png'),dpi=180)
    svg=args.output.with_suffix('.svg')
    fig.savefig(svg,metadata={'Date':evidence['date']})
    svg.write_text('\n'.join(line.rstrip() for line in svg.read_text().splitlines())+'\n')
    plt.close(fig)


if __name__=='__main__': main()
