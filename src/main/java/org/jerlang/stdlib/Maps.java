package org.jerlang.stdlib;

import org.jerlang.stdlib.maps.MapsFind;
import org.jerlang.stdlib.maps.MapsFold;
import org.jerlang.stdlib.maps.MapsForeach;
import org.jerlang.stdlib.maps.MapsFromList;
import org.jerlang.stdlib.maps.MapsGet;
import org.jerlang.stdlib.maps.MapsIterator;
import org.jerlang.stdlib.maps.MapsNext;
import org.jerlang.stdlib.maps.MapsPut;
import org.jerlang.stdlib.maps.MapsRemove;
import org.jerlang.stdlib.maps.MapsTake;
import org.jerlang.stdlib.maps.MapsUpdateWith;
import org.jerlang.stdlib.maps.MapsWith;
import org.jerlang.stdlib.maps.MapsIsKey;
import org.jerlang.stdlib.maps.MapsKeys;
import org.jerlang.stdlib.maps.MapsMap;
import org.jerlang.stdlib.maps.MapsMerge;
import org.jerlang.stdlib.maps.MapsNew;
import org.jerlang.stdlib.maps.MapsSize;
import org.jerlang.stdlib.maps.MapsToList;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Term;

/**
 * = maps
 *
 * == Summary
 *
 * Maps Processing Functions
 *
 * == Description
 *
 * This module contains functions for maps processing.
 *
 * Based on:
 * http://www.erlang.org/doc/man/maps.html
 */
public class Maps {

    public Term find(Term key, Map map) {
        return MapsFind.find_2(key, map);
    }

    /**
     * http://www.erlang.org/doc/man/maps.html#get-2
     */
    public static Term get(Term key, Map map) {
        return MapsGet.get_2(key, map);
    }

    /**
     * http://www.erlang.org/doc/man/maps.html#get-3
     */
    public static Term get(Term key, Map map, Term defaultValue) {
        return MapsGet.get_3(key, map, defaultValue);
    }

    /**
     * http://www.erlang.org/doc/man/maps.html#put-3
     */
    public static Map put(Term key, Term value, Map map) {
        return MapsPut.put_3(key, value, map);
    }

    /**
     * http://www.erlang.org/doc/man/maps.html#remove-2
     */
    public static Map remove(Term key, Map map) {
        return MapsRemove.remove_2(key, map);
    }

    /**
     * http://www.erlang.org/doc/man/maps.html#fold-3
     */
    public static Term fold(Term fun, Term init, Map map) {
        return MapsFold.fold_3(fun, init, map);
    }

    /**
     * http://www.erlang.org/doc/man/maps.html#foreach-2
     */
    public static Term foreach(Term fun, Map map) {
        return MapsForeach.foreach_2(fun, map);
    }

    /**
     * http://www.erlang.org/doc/man/maps.html#update_with-3
     */
    public static Map update_with(Term key, Term fun, Map map) {
        return MapsUpdateWith.update_with_3(key, fun, map);
    }

    /**
     * http://www.erlang.org/doc/man/maps.html#update_with-4
     */
    public static Map update_with(Term key, Term fun, Term init, Map map) {
        return MapsUpdateWith.update_with_4(key, fun, init, map);
    }

    /**
     * http://www.erlang.org/doc/man/maps.html#with-2
     */
    public static Map with(List keys, Map map) {
        return MapsWith.with_2(keys, map);
    }

    /**
     * http://www.erlang.org/doc/man/maps.html#take-2
     */
    public static Term take(Term key, Map map) {
        return MapsTake.take_2(key, map);
    }

    /**
     * http://www.erlang.org/doc/man/maps.html#iterator-1
     */
    public static Term iterator(Map map) {
        return MapsIterator.iterator_1(map);
    }

    /**
     * http://www.erlang.org/doc/man/maps.html#next-1
     */
    public static Term next(Term iterator) {
        return MapsNext.next_1(iterator);
    }

    /**
     * http://www.erlang.org/doc/man/maps.html#from_list-1
     */
    public static Map from_list(List pairs) {
        return MapsFromList.from_list_1(pairs);
    }

    public static boolean is_key(Term key, Map map) {
        return MapsIsKey.is_key_2(key, map);
    }

    public static List keys(Map map) {
        return MapsKeys.keys_1(map);
    }

    public static Map map(Term fun, Map map1) {
        return MapsMap.map_2(fun, map1);
    }

    /**
     * http://www.erlang.org/doc/man/maps.html#merge-2
     */
    public static Map merge(Map map1, Map map2) {
        return MapsMerge.merge_2(map1, map2);
    }

    public static Map _new() {
        return MapsNew.new_0();
    }

    public Integer size(Map map) {
        return MapsSize.size_1(map);
    }

    /**
     * The key-value pairs of a map, in key order.
     */
    public static List to_list(Map map) {
        return MapsToList.to_list_1(map);
    }

}
