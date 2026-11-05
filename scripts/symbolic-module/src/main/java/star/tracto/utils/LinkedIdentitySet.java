package star.tracto.utils;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * A set of distinct objects by identity ({@code ==}, as a set made from an {@link IdentityHashMap}) iterated in the
 * order of insertion (Step 24). A set made from an {@code IdentityHashMap} is iterated in the order of the identity
 * hash codes, which changes from one run to another and with any change of the allocations; the AST nodes collected in
 * it (for example the declarators of the fields offered as candidates) came out in a different order.
 *
 * <p>Only adding and reading are supported (the uses of the collectors); removing throws.</p>
 *
 * @param <E> the type of the elements
 */
public final class LinkedIdentitySet<E> extends AbstractSet<E> {

    private final Set<E> members = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<E> order = new ArrayList<>();

    @Override
    public boolean add(E element) {
        if (!members.add(element)) {
            return false;
        }
        order.add(element);
        return true;
    }

    @Override
    @SuppressWarnings("SuspiciousMethodCalls")
    public boolean contains(Object element) {
        return members.contains(element);
    }

    @Override
    public Iterator<E> iterator() {
        return Collections.unmodifiableList(order).iterator();
    }

    @Override
    public int size() {
        return order.size();
    }
}
