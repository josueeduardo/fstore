package io.joshworks.fstore.log.appender.level;

import io.joshworks.fstore.log.Log;
import io.joshworks.fstore.log.appender.Order;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

public class Levels<T, L extends Log<T>> {

    private final List<List<L>> items;
    private final int maxItemsPerLevel;
    private LinkedList<L> flattened;

    private Levels(int maxItemsPerLevel, List<List<L>> levels) {
        this.maxItemsPerLevel = maxItemsPerLevel;
        this.items = levels;
        this.flattened = flatten();
    }

    public List<L> segments(int level) {
        return items.get(level);
    }

    public L get(int segmentIdx) {
        if (segmentIdx == 0) {
            return current();
        }
        return flattened.get(segmentIdx);
    }

    public int depth() {
        return items.size();
    }

    public static <T, L extends Log<T>> Levels<T, L> load(int maxItemsPerLevel, List<List<L>> segments) {
        return new Levels<>(maxItemsPerLevel, segments);
    }

    public List<List<String>> segmentNames() {
        return items.stream()
                .map(segs -> segs.stream().map(Log::name).collect(Collectors.toList()))
                .collect(Collectors.toList());
    }

    public void promoteLevelZero(L newLevelZero) {
        List<L> levelZero = items.get(0);
        if (levelZero == null) {
            throw new IllegalStateException("No segment available in level zero");
        }
        if (depth() == 1) {
            items.add(new ArrayList<>(maxItemsPerLevel));
        }
        L toBePromoted = items.get(0).remove(0);
        toBePromoted.roll();
//        String prefix = strategy.prefix();
//        String newFileName = LogFileUtils.fileName(prefix, items.get(1).size(), 1);

//        toBePromoted.renameTo(newFileName);

        if (!toBePromoted.readOnly()) {
            throw new IllegalStateException("Segment must be marked as read only after rolling");
        }

        items.get(1).add(toBePromoted);
        items.get(0).add(newLevelZero);

        this.flattened = flatten();
    }

    private LinkedList<L> flatten() {
        return items.stream().flatMap(Collection::stream).collect(Collectors.toCollection(LinkedList::new));
    }

    public int numSegments() {
        return flattened.size();
    }

    public int size(int level) {
        if(level < 0) {
            throw new IllegalArgumentException("Level must be at least zero");
        }
        if(level >= depth()) {
            return 0;
        }
        return segments(level).size();
    }

    public boolean requiresCompaction(int level) {
        if (level < 0 || level >= depth()) {
            return false;
        }
        return items.get(level).size() >= maxItemsPerLevel;
    }

    public void add(int level, L segment) {
        if (depth() <= level) {
            items.add(new ArrayList<>(maxItemsPerLevel));
        }
        items.get(level).add(segment);

        this.flattened = flatten();
    }

    public List<L> segmentsForCompaction(int level) {
        List<L> segments = segments(level);
        return segments.size() > maxItemsPerLevel ? segments.subList(0, maxItemsPerLevel) : segments;
    }

    public void removeSegmentsFromCompaction(int level) {
        Iterator<L> segments = segmentsForCompaction(level).iterator();
        while(segments.hasNext()) {
            L segment = segments.next();
            segment.delete();
            segments.remove();
        }
    }

    //TODO safely swap segments
    public void deleteSegments(int level) {
        if (depth() < level) {
            throw new IllegalArgumentException("No such level " + level + ", current depth: " + depth());
        }
        if (level == 0) {
            throw new IllegalArgumentException("Cannot delete level zero");
        }
        Iterator<L> segments = items.get(level).iterator();
        while(segments.hasNext()) {
            segments.next().delete();
            segments.remove();
        }

        this.flattened = flatten();
    }

    public L current() {
        return flattened.get(0);
    }

    public Iterator<L> segments(Order order) {
        if(Order.OLDEST.equals(order)) {
            return flattened.descendingIterator();
        }
        return flattened.iterator();

    }


    @Override
    public String toString() {
        return "Levels{" + "depth=" + depth() + ", items=" + segmentNames() + '}';
    }
}
