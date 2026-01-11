import os
import pandas as pd
import matplotlib.pyplot as plt

def get_run_info(results_dir='results'):
    """Сканирует папку results и собирает информацию о прогонах."""
    if not os.path.exists(results_dir):
        return []

    runs = []
    folders = [f for f in os.listdir(results_dir) if f.startswith('run_')]
    for folder in sorted(folders, reverse=True):
        path = os.path.join(results_dir, folder)
        config_file = os.path.join(path, 'config.txt')
        metrics_file = os.path.join(path, 'metrics.csv')

        if os.path.exists(config_file) and os.path.exists(metrics_file):
            with open(config_file, 'r') as f:
                params = f.read()
            runs.append({'id': folder, 'path': path, 'params': params, 'metrics': metrics_file})
    return runs

def plot_single_run(run):
    """Строит графики для одного конкретного прогона."""
    df = pd.read_csv(run['metrics'])
    # Исправлено: используем 'table' вместо 'table_name'
    df.plot(x='table', y=['uuid_idx_mb', 'int_idx_mb'], kind='bar', figsize=(12, 6))

    plt.title(f"Анализ прогона: {run['id']}\n{run['params'].splitlines()[1]}")
    plt.ylabel('Размер индекса (МБ)')
    plt.grid(axis='y', linestyle='--')
    plt.tight_layout()

    # Сохраняем результат в архивную папку прогона
    save_path = os.path.join(run['path'], 'summary_plot.png')
    plt.savefig(save_path)
    print(f"\n[OK] График сохранен в: {save_path}")
    plt.show()

def compare_multiple_runs(selected_runs):
    """Сравнивает общую эффективность нескольких прогонов."""
    summary = []
    for run in selected_runs:
        df = pd.read_csv(run['metrics'])
        total_uuid = df['uuid_idx_mb'].sum()
        total_int = df['int_idx_mb'].sum()
        efficiency = (1 - total_int / total_uuid) * 100 if total_uuid > 0 else 0

        summary.append({
            'Run': run['id'],
            'UUID Total (MB)': total_uuid,
            'INT Total (MB)': total_int,
            'Efficiency %': efficiency
        })

    summary_df = pd.DataFrame(summary)
    print("\n--- СРАВНИТЕЛЬНАЯ ТАБЛИЦА ЭФФЕКТИВНОСТИ ---")
    print(summary_df.to_string(index=False))

    summary_df.plot(x='Run', y=['UUID Total (MB)', 'INT Total (MB)'], kind='bar')
    plt.title('Сравнение общего объема индексов')
    plt.ylabel('МБ')
    plt.tight_layout()
    plt.show()

def main():
    runs = get_run_info()
    if not runs:
        print("Папка results пуста или не создана.")
        return

    print("\n--- ДОСТУПНЫЕ ПРОГОНЫ ---")
    for i, run in enumerate(runs):
        # Извлекаем кол-во записей из первой строки параметров
        print(f"[{i}] {run['id']} | {run['params'].splitlines()[1]}")

    choice = input("\nВведите номера прогонов через запятую (напр. 0,1) или 'all': ")

    try:
        if choice.lower() == 'all':
            selected = runs
        else:
            indices = [int(x.strip()) for x in choice.split(',')]
            selected = [runs[i] for i in indices]

        if len(selected) == 1:
            plot_single_run(selected[0])
        else:
            compare_multiple_runs(selected)

    except Exception as e:
        print(f"Ошибка при обработке: {e}")

if __name__ == "__main__":
    main()