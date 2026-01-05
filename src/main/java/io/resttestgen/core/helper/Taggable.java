package io.resttestgen.core.helper;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Class that enable subclasses to store and manage tags.
 */
public class Taggable {

    public final HashSet<String> tags = new HashSet<>();
    public final Map<String, Object> metadata = new HashMap<>();

    public Set<String> getTags() {
        return Collections.unmodifiableSet(tags);
    }

    public boolean addTag(String tag) {
        return tags.add(tag);
    }

    public boolean removeTag(String tag) {
        return tags.remove(tag);
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    public void addTag(String key, Object value) {
        metadata.put(key, value);
    }

    public Object getTag(String key) {
        return metadata.get(key);
    }
}
