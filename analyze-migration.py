# Migration Metrics Collector & Analyzer
# Collects: migration stats, connection usage, performance comparison (UUID vs BIGINT)
# Usage: python analyze-migration.py [--dir performance_logs] [--compare]

import os
import sys
import glob
import json
import argparse
from datetime import datetime
from pathlib import Path

try:
    import pandas as pd
    HAS_PANDAS = True
except ImportError:
    HAS_PANDAS = False

try:
    import matplotlib
    matplotlib.use('Agg')  # Non-interactive backend
    import matplotlib.pyplot as plt
    HAS_MATPLOTLIB = True
except ImportError:
    HAS_MATPLOTLIB = False


def find_latest_logs(logs_dir='performance_logs'):
    """Find the latest performance logs directory."""
    if not os.path.exists(logs_dir):
        return None
    files = glob.glob(os.path.join(logs_dir, 'summary_*.txt'))
    if not files:
        return None
    latest = max(files, key=os.path.getmtime)
    ts = os.path.basename(latest).replace('summary_', '').replace('.txt', '')
    return {
        'timestamp': ts,
        'summary': latest,
        'batch': os.path.join(logs_dir, f'batch_performance_{ts}.csv'),
        'mapping': os.path.join(logs_dir, f'mapping_performance_{ts}.csv'),
        'pool': os.path.join(logs_dir, f'connection_pool_{ts}.csv'),
    }


def parse_summary(summary_file):
    """Parse the summary text file for key metrics."""
    metrics = {}
    tables = []
    current_table = None

    with open(summary_file, 'r', encoding='utf-8', errors='replace') as f:
        for line in f:
            line = line.strip()

            if line.startswith('Table:'):
                current_table = {'name': line.split(':', 1)[1].strip()}
            elif line.startswith('Total records:') and current_table:
                current_table['total_records'] = int(line.split(':', 1)[1].strip().replace(',', ''))
            elif line.startswith('Total duration:') and current_table:
                dur = line.split(':', 1)[1].strip().replace('s', '')
                current_table['duration_s'] = float(dur)
            elif line.startswith('Average speed:') and current_table:
                speed = line.split(':', 1)[1].strip().replace('rec/sec', '')
                current_table['avg_speed'] = float(speed)
            elif line.startswith('Batches:') and current_table:
                current_table['batches'] = int(line.split(':', 1)[1].strip())
            elif line.startswith('Batch time (ms):') and current_table:
                parts = line.split(':', 1)[1].strip()
                for p in parts.split(','):
                    k, v = p.strip().split('=')
                    current_table[f'batch_{k.strip()}'] = int(v)
            elif line.startswith('Insert time (ms):') and current_table:
                parts = line.split(':', 1)[1].strip()
                for p in parts.split(','):
                    k, v = p.strip().split('=')
                    current_table[f'insert_{k.strip()}'] = int(v)
            elif line.startswith('Mapping time (ms):') and current_table:
                parts = line.split(':', 1)[1].strip()
                for p in parts.split(','):
                    k, v = p.strip().split('=')
                    current_table[f'mapping_{k.strip()}'] = int(v)
                tables.append(current_table)
                current_table = None

    metrics['tables'] = tables
    metrics['total_records'] = sum(t.get('total_records', 0) for t in tables)
    metrics['total_duration_s'] = sum(t.get('duration_s', 0) for t in tables)
    if metrics['total_duration_s'] > 0:
        metrics['overall_speed'] = metrics['total_records'] / metrics['total_duration_s']
    else:
        metrics['overall_speed'] = 0

    return metrics


def parse_batch_csv(csv_file):
    """Parse batch performance CSV."""
    if not os.path.exists(csv_file):
        return None
    df = pd.read_csv(csv_file)
    return df


def parse_pool_csv(csv_file):
    """Parse connection pool CSV."""
    if not os.path.exists(csv_file):
        return None
    df = pd.read_csv(csv_file)
    return df


def print_migration_report(metrics):
    """Print formatted migration report."""
    print("\n" + "=" * 70)
    print("  MIGRATION PERFORMANCE REPORT")
    print("=" * 70)

    tables = metrics.get('tables', [])
    total_records = metrics.get('total_records', 0)
    total_duration = metrics.get('total_duration_s', 0)
    overall_speed = metrics.get('overall_speed', 0)

    print(f"\n  Total records migrated:  {total_records:>12,}")
    print(f"  Total duration:          {total_duration:>12.1f}s")
    print(f"  Overall speed:           {overall_speed:>12,.0f} rec/sec")
    print(f"  Tables processed:        {len(tables):>12}")

    print(f"\n  {'Table':<25} {'Records':>10} {'Duration':>10} {'Speed':>12} {'Batches':>8}")
    print(f"  {'-'*25} {'-'*10} {'-'*10} {'-'*12} {'-'*8}")

    for t in tables:
        name = t.get('name', '?')[:24]
        rec = t.get('total_records', 0)
        dur = t.get('duration_s', 0)
        spd = t.get('avg_speed', 0)
        bat = t.get('batches', 0)
        print(f"  {name:<25} {rec:>10,} {dur:>9.1f}s {spd:>11,.0f}/s {bat:>8}")

    print("\n" + "-" * 70)
    print("  BOTTLENECK ANALYSIS")
    print("-" * 70)

    if tables:
        slowest = max(tables, key=lambda t: t.get('duration_s', 0))
        fastest = max(tables, key=lambda t: t.get('avg_speed', 0))
        slowest_batch = max(tables, key=lambda t: t.get('batch_max', 0))

        print(f"\n  Slowest table:     {slowest.get('name', '?')} ({slowest.get('duration_s', 0):.1f}s)")
        print(f"  Fastest table:     {fastest.get('name', '?')} ({fastest.get('avg_speed', 0):,.0f} rec/sec)")
        print(f"  Highest variance:  {slowest_batch.get('name', '?')} (batch {slowest_batch.get('batch_min', 0)}-{slowest_batch.get('batch_max', 0)}ms)")

    print("\n" + "=" * 70)


def print_connection_report(pool_df):
    """Print connection pool analysis."""
    if pool_df is None or pool_df.empty:
        print("\n  [i] No connection pool data available")
        return

    print("\n" + "=" * 70)
    print("  CONNECTION POOL ANALYSIS")
    print("=" * 70)

    active = pool_df['active_connections']
    idle = pool_df['idle_connections']
    total = pool_df['total_connections']
    waiting = pool_df['waiting_threads']

    print(f"\n  Active connections:  min={active.min():>4}  max={active.max():>4}  avg={active.mean():>6.1f}")
    print(f"  Idle connections:    min={idle.min():>4}  max={idle.max():>4}  avg={idle.mean():>6.1f}")
    print(f"  Total connections:   min={total.min():>4}  max={total.max():>4}  avg={total.mean():>6.1f}")
    print(f"  Waiting threads:     min={waiting.min():>4}  max={waiting.max():>4}  avg={waiting.mean():>6.1f}")

    max_waiting = waiting.max()
    if max_waiting > 0:
        print(f"\n  [!] WARNING: {max_waiting} threads waited for connections!")
        print(f"      Recommendation: increase maxPoolSize")
    else:
        print(f"\n  [+] No connection contention detected")

    print("\n" + "=" * 70)


def print_batch_analysis(batch_df):
    """Print batch performance analysis."""
    if batch_df is None or batch_df.empty:
        print("\n  [i] No batch performance data available")
        return

    print("\n" + "=" * 70)
    print("  BATCH PERFORMANCE ANALYSIS")
    print("=" * 70)

    tables = batch_df['table'].unique()

    print(f"\n  {'Table':<25} {'Avg r/s':>10} {'Min batch':>10} {'Max batch':>10} {'Avg insert':>11} {'Avg mapping':>11}")
    print(f"  {'-'*25} {'-'*10} {'-'*10} {'-'*10} {'-'*11} {'-'*11}")

    for table in tables:
        tdf = batch_df[batch_df['table'] == table]
        avg_rps = tdf['records_per_sec'].mean()
        min_b = tdf['total_batch_ms'].min()
        max_b = tdf['total_batch_ms'].max()
        avg_ins = tdf['insert_duration_ms'].mean()
        avg_map = tdf['mapping_duration_ms'].mean()
        print(f"  {table:<25} {avg_rps:>10,.0f} {min_b:>9}ms {max_b:>9}ms {avg_ins:>10.0f}ms {avg_map:>10.0f}ms")

    # Find slowest batches
    slowest = batch_df.nlargest(5, 'total_batch_ms')
    print(f"\n  Top 5 slowest batches:")
    for _, row in slowest.iterrows():
        print(f"    {row['table']} batch {row['batch_number']}: {row['total_batch_ms']}ms "
              f"(insert={row['insert_duration_ms']}ms, mapping={row['mapping_duration_ms']}ms)")

    print("\n" + "=" * 70)


def generate_charts(logs, output_dir=None):
    """Generate performance charts."""
    if not HAS_MATPLOTLIB:
        print("\n  [i] matplotlib not installed - skipping charts")
        return

    batch_df = parse_batch_csv(logs['batch'])
    pool_df = parse_pool_csv(logs['pool'])

    if batch_df is None:
        return

    if output_dir is None:
        output_dir = os.path.join('performance_logs', f'charts_{logs["timestamp"]}')
    os.makedirs(output_dir, exist_ok=True)

    # Chart 1: Batch performance over time
    fig, axes = plt.subplots(2, 2, figsize=(16, 10))
    fig.suptitle(f'Migration Performance - {logs["timestamp"]}', fontsize=14)

    # Insert time by table
    tables = batch_df['table'].unique()
    colors = plt.cm.Set3(range(len(tables)))

    ax = axes[0, 0]
    for i, table in enumerate(tables):
        tdf = batch_df[batch_df['table'] == table]
        ax.bar(table, tdf['insert_duration_ms'].mean(), color=colors[i], label=table)
    ax.set_ylabel('Avg Insert (ms)')
    ax.set_title('Average Insert Time by Table')
    ax.tick_params(axis='x', rotation=45)

    # Mapping time by table
    ax = axes[0, 1]
    for i, table in enumerate(tables):
        tdf = batch_df[batch_df['table'] == table]
        ax.bar(table, tdf['mapping_duration_ms'].mean(), color=colors[i], label=table)
    ax.set_ylabel('Avg Mapping (ms)')
    ax.set_title('Average Mapping Time by Table')
    ax.tick_params(axis='x', rotation=45)

    # Records/sec by table
    ax = axes[1, 0]
    for i, table in enumerate(tables):
        tdf = batch_df[batch_df['table'] == table]
        ax.bar(table, tdf['records_per_sec'].mean(), color=colors[i], label=table)
    ax.set_ylabel('Records/sec')
    ax.set_title('Throughput by Table')
    ax.tick_params(axis='x', rotation=45)

    # Batch time distribution
    ax = axes[1, 1]
    ax.hist(batch_df['total_batch_ms'], bins=50, edgecolor='black', alpha=0.7)
    ax.set_xlabel('Batch Time (ms)')
    ax.set_ylabel('Count')
    ax.set_title('Batch Time Distribution')

    plt.tight_layout()
    chart_path = os.path.join(output_dir, 'performance.png')
    plt.savefig(chart_path, dpi=150)
    plt.close()
    print(f"\n  [+] Charts saved to: {output_dir}/")


def save_json_report(metrics, batch_df, pool_df, output_path=None):
    """Save all metrics as JSON for external analysis."""
    if output_path is None:
        output_path = os.path.join('performance_logs', f'metrics_{datetime.now().strftime("%Y%m%d_%H%M%S")}.json')

    report = {
        'timestamp': datetime.now().isoformat(),
        'migration': metrics,
        'connection_pool': None,
        'batch_summary': None,
    }

    if pool_df is not None and not pool_df.empty:
        report['connection_pool'] = {
            'active': {'min': int(pool_df['active_connections'].min()),
                       'max': int(pool_df['active_connections'].max()),
                       'avg': round(float(pool_df['active_connections'].mean()), 1)},
            'idle': {'min': int(pool_df['idle_connections'].min()),
                     'max': int(pool_df['idle_connections'].max()),
                     'avg': round(float(pool_df['idle_connections'].mean()), 1)},
            'waiting': {'max': int(pool_df['waiting_threads'].max())},
        }

    if batch_df is not None and not batch_df.empty:
        batch_summary = []
        for table in batch_df['table'].unique():
            tdf = batch_df[batch_df['table'] == table]
            batch_summary.append({
                'table': table,
                'avg_records_per_sec': round(float(tdf['records_per_sec'].mean()), 0),
                'avg_batch_ms': round(float(tdf['total_batch_ms'].mean()), 0),
                'avg_insert_ms': round(float(tdf['insert_duration_ms'].mean()), 0),
                'avg_mapping_ms': round(float(tdf['mapping_duration_ms'].mean()), 0),
            })
        report['batch_summary'] = batch_summary

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, 'w') as f:
        json.dump(report, f, indent=2)

    print(f"\n  [+] JSON report saved to: {output_path}")
    return output_path


def main():
    parser = argparse.ArgumentParser(description='Migration Metrics Analyzer')
    parser.add_argument('--dir', default='performance_logs', help='Performance logs directory')
    parser.add_argument('--charts', action='store_true', help='Generate performance charts')
    parser.add_argument('--json', action='store_true', help='Save JSON report')
    parser.add_argument('--latest', action='store_true', help='Analyze latest run only')
    args = parser.parse_args()

    logs = find_latest_logs(args.dir)
    if not logs:
        print(f"[x] No performance logs found in {args.dir}")
        print("    Run a migration first: ./gradlew run --args='copy'")
        sys.exit(1)

    print(f"\n  Analyzing: {logs['timestamp']}")

    # Parse summary
    metrics = parse_summary(logs['summary'])
    print_migration_report(metrics)

    # Parse batch CSV
    batch_df = None
    if HAS_PANDAS:
        batch_df = parse_batch_csv(logs['batch'])
        print_batch_analysis(batch_df)

    # Parse pool CSV
    pool_df = None
    if HAS_PANDAS:
        pool_df = parse_pool_csv(logs['pool'])
        print_connection_report(pool_df)

    # Generate charts
    if args.charts:
        generate_charts(logs)

    # Save JSON
    if args.json:
        save_json_report(metrics, batch_df, pool_df)

    print("\n  Done.")


if __name__ == '__main__':
    main()
