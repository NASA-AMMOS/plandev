from bokeh.io import output_notebook
from bokeh.plotting import figure
import numpy as np

from pymerlin.duration import Duration
from bokeh.models import LabelSet, ColumnDataSource, HBar

output_notebook()

def plot_profiles(profiles, duration, x_range=None):
    simulation_duration = Duration.from_string(duration)
    if x_range is None:
        x_range = (0, simulation_duration.micros)
    p = figure(y_range=list(reversed(list(profiles))), x_range=x_range, width=400, height=len(profiles) * 65, toolbar_location=None, title="Resources")
    
    left = []
    right = []
    y = []
    label = []
    for profile, segments in profiles.items():
        elapsed = 0
        for segment in segments:
            y.append(profile)
            left.append(elapsed)
            right.append(elapsed + segment.extent.micros)
            label.append(str(segment.dynamics))
            elapsed += segment.extent.micros
        
    spans_bars = {
        "y": y,
        "left": left,
        "right": right
    }
    
    spans_bars = ColumnDataSource(spans_bars)
    glyph = HBar(y="y", right="right", left="left", height=0.9, fill_color="#b3de69")
    p.add_glyph(spans_bars, glyph)
    
    p.ygrid.grid_line_color = None
    p.xaxis.axis_label = "Time (microseconds)"
    p.outline_line_color = None
    
    labels = {
        'x': [((l + r) / 2) - 200000000 for l, r in zip(left, right)],
        "y": y,
        'labels': label
    }
    
    p.add_layout(LabelSet(x="x", y="y", text="labels", text_font_size="11px", text_color="#555555",
                      source=ColumnDataSource(labels), text_align='left'))
    
    return p

def plot_spans(spans, duration):
    simulation_duration = Duration.from_string(duration)
    
    p = figure(y_range=(len(spans), -1), x_range=(0, simulation_duration.micros), width=400, height=(1 + len(spans)) * 40, toolbar_location=None,
               title="Spans")
    
    left = [span.start.micros for span in spans]
    right = [((span.start.micros + span.duration.micros) if span.duration is not None else np.inf) for span in spans]
    
    spans_bars = ColumnDataSource({
        "y": list(range(len(spans))),
        "left": left,
        "right": right
    })
    
    
    glyph = HBar(y="y", right="right", left="left", height=0.9, fill_color="#b3de69")
    p.add_glyph(spans_bars, glyph)
    
    p.ygrid.grid_line_color = None
    p.xaxis.axis_label = "Time (microseconds)"
    p.outline_line_color = None
    
    labels = {
        'x': [
            span.start.micros + 100000000 for span in spans
        ],
        'y': [i - 0.45 for i in range(len(spans))],
        'labels': [
            span.type for span in spans
        ]
    }
    
    p.add_layout(LabelSet(x="x", y="y", text="labels", text_font_size="11px", text_color="#555555",
                      source=ColumnDataSource(labels), text_align='left'))
    
    p.yaxis.visible=False
    return p