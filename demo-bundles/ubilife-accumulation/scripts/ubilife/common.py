"""Shared helpers for the UbiLife scripts: reading stream tables and windows."""

import pandas as pd


def load_stream(path, columns=None):
    """Reads a stream table from split_streams.py, with `timestamp` as datetime (UTC, naive)."""
    df = pd.read_csv(path, dtype=str, keep_default_na=False)
    # A stream with no rows yet has only its fixed columns; the rest are empty, not missing.
    for column in columns or []:
        if column not in df.columns:
            df[column] = pd.Series(dtype=str)
    if columns:
        df = df[columns]
    df["timestamp"] = pd.to_datetime(pd.to_numeric(df["timestamp"], errors="coerce"), unit="us")
    return df


def window(start, end):
    """A half-open window [start, end) from two ISO dates or date-times."""
    return pd.Timestamp(start), pd.Timestamp(end)


def in_window(df, start, end, column="timestamp"):
    return df[(df[column] >= start) & (df[column] < end)].copy()


def numeric(series):
    return pd.to_numeric(series, errors="coerce")
