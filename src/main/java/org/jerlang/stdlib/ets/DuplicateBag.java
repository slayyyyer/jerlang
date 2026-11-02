package org.jerlang.stdlib.ets;

import org.jerlang.type.Atom;

/** An ETS duplicate_bag: every inserted object is retained. */
public class DuplicateBag extends Bag {

    public DuplicateBag(Access access, Atom name, boolean named_table,
                        boolean compressed, int keypos) {
        super(Type.DUPLICATE_BAG, access, name, named_table, compressed, keypos);
    }

}
