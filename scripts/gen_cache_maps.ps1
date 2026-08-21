# Generates the cache hash map classes for boolean, byte, short, double, float and char:
#   O2{Z,B,S,D,F,C}OpenCacheHashMap        (from O2IOpenCacheHashMap template)
#   O2{Z,B,S,D,F,C}OpenCustomCacheHashMap  (from O2IOpenCustomCacheHashMap template)
# Run from the repository root:
#   powershell -ExecutionPolicy Bypass -File scripts\gen_cache_maps.ps1

$ErrorActionPreference = 'Stop'

$srcDir = Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..')).Path 'src\main\java\com\gto\fastcollection'

function Read-Template($name) {
    Get-Content (Join-Path $srcDir "$name.java") -Raw -Encoding UTF8
}

function Write-File($path, $content) {
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($path, $content, $utf8)
}

# Per-type configuration.
$types = @(
    @{ Code = 'Z'; Name = 'Boolean';   Pkg = 'booleans'; Value = 'boolean'; Wrapper = 'Boolean';   Prim = 'boolean'; Consumer = 'BooleanConsumer';   Operator = $null },
    @{ Code = 'B'; Name = 'Byte';      Pkg = 'bytes';    Value = 'byte';    Wrapper = 'Byte';      Prim = 'byte';    Consumer = 'ByteConsumer';      Operator = 'it.unimi.dsi.fastutil.bytes.ByteBinaryOperator' },
    @{ Code = 'S'; Name = 'Short';     Pkg = 'shorts';   Value = 'short';   Wrapper = 'Short';     Prim = 'short';    Consumer = 'ShortConsumer';     Operator = 'it.unimi.dsi.fastutil.shorts.ShortBinaryOperator' },
    @{ Code = 'D'; Name = 'Double';    Pkg = 'doubles';  Value = 'double';  Wrapper = 'Double';    Prim = 'double';   Consumer = 'java.util.function.DoubleConsumer'; Operator = 'java.util.function.DoubleBinaryOperator' },
    @{ Code = 'F'; Name = 'Float';     Pkg = 'floats';   Value = 'float';   Wrapper = 'Float';     Prim = 'float';    Consumer = 'FloatConsumer';     Operator = 'it.unimi.dsi.fastutil.floats.FloatBinaryOperator' },
    @{ Code = 'C'; Name = 'Char';      Pkg = 'chars';    Value = 'char';    Wrapper = 'Character'; Prim = 'char';     Consumer = 'CharConsumer';      Operator = 'it.unimi.dsi.fastutil.chars.CharBinaryOperator' }
)

function Transform($content, $t, $isCustom) {
    # normalize line endings: the templates are CRLF; the heredoc blocks below are LF
    $content = $content -replace "`r`n", "`n"
    $code = $t.Code; $name = $t.Name; $pkg = $t.Pkg; $value = $t.Value
    $wrapper = $t.Wrapper; $prim = $t.Prim; $consumer = $t.Consumer; $op = $t.Operator
    $className = "O2${code}Open$(if ($isCustom) { 'Custom' })CacheHashMap"
    $hashLine = if ($isCustom) { '            h = strategy.hashCode(k);' } else { '            h = k.hashCode();' }

    # ---- 0. Type-specific method-block surgery (done first, on raw template text) ----

    # 0a. computeIfAbsent(K, ToIntFunction) -> type-specific functional method.
    $toIntBlock = @"
    @Override
    public int computeIfAbsent(final K k, final ToIntFunction<? super K> mappingFunction) {
        int pos, h = 0;
        if (k == null) {
            pos = containsNullKey ? n : -(n + 1);
        } else {
$hashLine
            pos = find(k, h);
        }
        if (pos >= 0) return value[pos];
        final int newValue = mappingFunction.applyAsInt(k);
        insert(-pos - 1, k, newValue, h);
        return newValue;
    }
"@
    $repl = $null
    if ($code -eq 'Z') {
        $repl = @"
    @Override
    public boolean computeIfAbsent(final K k, final java.util.function.Predicate<? super K> mappingFunction) {
        int pos, h = 0;
        if (k == null) {
            pos = containsNullKey ? n : -(n + 1);
        } else {
$hashLine
            pos = find(k, h);
        }
        if (pos >= 0) return value[pos];
        final boolean newValue = mappingFunction.test(k);
        insert(-pos - 1, k, newValue, h);
        return newValue;
    }
"@
    } elseif ($code -eq 'D') {
        $repl = $toIntBlock.Replace('public int computeIfAbsent(final K k, final ToIntFunction<? super K> mappingFunction) {', 'public double computeIfAbsent(final K k, final java.util.function.ToDoubleFunction<? super K> mappingFunction) {')
        $repl = $repl.Replace('final int newValue = mappingFunction.applyAsInt(k);', 'final double newValue = mappingFunction.applyAsDouble(k);')
    } elseif ($code -eq 'F') {
        $repl = $toIntBlock.Replace('public int computeIfAbsent(final K k, final ToIntFunction<? super K> mappingFunction) {', 'public float computeIfAbsent(final K k, final java.util.function.ToDoubleFunction<? super K> mappingFunction) {')
        $repl = $repl.Replace('final int newValue = mappingFunction.applyAsInt(k);', 'final float newValue = it.unimi.dsi.fastutil.SafeMath.safeDoubleToFloat(mappingFunction.applyAsDouble(k));')
    } else {
        # byte / short / char keep ToIntFunction
        $repl = $toIntBlock.Replace('public int computeIfAbsent(final K k, final ToIntFunction<? super K> mappingFunction) {', "public ${value} computeIfAbsent(final K k, final ToIntFunction<? super K> mappingFunction) {")
        if ($code -eq 'B') {
            $repl = $repl.Replace('final int newValue = mappingFunction.applyAsInt(k);', 'final byte newValue = it.unimi.dsi.fastutil.SafeMath.safeIntToByte(mappingFunction.applyAsInt(k));')
        } elseif ($code -eq 'S') {
            $repl = $repl.Replace('final int newValue = mappingFunction.applyAsInt(k);', 'final short newValue = it.unimi.dsi.fastutil.SafeMath.safeIntToShort(mappingFunction.applyAsInt(k));')
        } elseif ($code -eq 'C') {
            $repl = $repl.Replace('final int newValue = mappingFunction.applyAsInt(k);', 'final char newValue = it.unimi.dsi.fastutil.SafeMath.safeIntToChar(mappingFunction.applyAsInt(k));')
        }
    }
    if ($null -ne $repl) { $content = $content.Replace($toIntBlock, $repl) }

    # 0b. boolean: drop addToValue / addTo / operator-merge (absent in Object2BooleanOpenHashMap).
    if ($code -eq 'Z') {
        # addToValue
        $content = [regex]::Replace($content, '(?m)^    private int addToValue\(final int pos, final int incr\) \{\r?\n(.*\r?\n)*?    \}\r?\n', '')
        # addTo (any equals form: k.equals / curr.equals / strategy.equals)
        $content = [regex]::Replace($content, '(?m)^    @Override\r?\n    public int addTo\(final K k, final int incr\) \{\r?\n(.*\r?\n)*?    \}\r?\n', '')
        # operator-merge (mergeInt / mergeXxx with XxxBinaryOperator, possibly fully qualified)
        $content = [regex]::Replace($content, '(?m)^    /\*\* \{@inheritDoc\} \*/\r?\n    @Override\r?\n    public \w+ merge\w+\(final K k, final \w+ v, [\w.]+BinaryOperator remappingFunction\) \{\r?\n(.*\r?\n)*?    \}\r?\n', '')
    }

    # ---- 1. Class / base / imports ----
    $content = $content.Replace('O2IOpenCacheHashMap', $className)
    $content = $content.Replace('O2IOpenCustomCacheHashMap', $className)
    if ($isCustom) {
        $content = $content.Replace("class ${className}<K> extends Object2IntOpenCustomHashMap<K>", "class ${className}<K> extends Object2${name}OpenCustomHashMap<K>")
    } else {
        $content = $content.Replace("class ${className}<K> extends Object2IntOpenHashMap<K>", "class ${className}<K> extends Object2${name}OpenHashMap<K>")
    }
    # boxed BiFunction generics: replace 'Integer' token only inside type args.
    # Order matters: '? super Integer' before '? extends Integer' (the former is not
    # a suffix of the latter, but the trailing comma form appears in compute/merge).
    $content = $content.Replace('? super Integer', "? super ${wrapper}")
    $content = $content.Replace('? extends Integer', "? extends ${wrapper}")
    $content = $content.Replace('Map.Entry<K, Integer>', "Map.Entry<K, ${wrapper}>")
    $content = $content.Replace('it.unimi.dsi.fastutil.ints.*', "it.unimi.dsi.fastutil.${pkg}.*")
    $content = $content.Replace('Object2IntMap', "Object2${name}Map")
    $content = $content.Replace('Object2IntFunction', "Object2${name}Function")
    $content = $content.Replace('ObjectIntPair', "Object${name}Pair")
    $content = $content.Replace('IntIterator', "${name}Iterator")
    $content = $content.Replace('IntSpliterator', "${name}Spliterator")
    $content = $content.Replace('IntSpliterators', "${name}Spliterators")
    $content = $content.Replace('IntCollection', "${name}Collection")
    $content = $content.Replace('AbstractIntCollection', "Abstract${name}Collection")
    $content = $content.Replace('java.util.function.IntConsumer', $consumer)

    # ---- 2. Value array types (hash arrays stay int[]) ----
    $content = $content.Replace('int[] value', "${value}[] value")
    $content = $content.Replace('final int[] newValue = new int[newN + 1];', "final ${value}[] newValue = new ${value}[newN + 1];")

    # ---- 3. Value method names ----
    $content = $content.Replace('removeInt', "remove${name}")
    $content = $content.Replace('getIntValue', "get${name}Value")
    $content = $content.Replace('rightInt', "right${name}")
    $content = $content.Replace('computeIntIfPresent', "compute${name}IfPresent")
    $content = $content.Replace('computeInt', "compute${name}")
    $content = $content.Replace('mergeInt', "merge${name}")
    $content = $content.Replace('getInt', "get${name}")
    $content = $content.Replace('nextInt', "next${name}")
    $content = $content.Replace('object2IntEntrySet', "object2${name}EntrySet")

    # ---- 4. Value-typed signatures and locals ----
    $content = $content.Replace('private int removeEntry(int pos)', "private ${value} removeEntry(int pos)")
    $content = $content.Replace('private int removeNullEntry()', "private ${value} removeNullEntry()")
    $content = $content.Replace('private int addToValue(final int pos, final int incr)', "private ${value} addToValue(final int pos, final ${value} incr)")
    $content = $content.Replace('private void insert(final int pos, final K k, final int v, final int h)', "private void insert(final int pos, final K k, final ${value} v, final int h)")
    $content = $content.Replace('public int put(final K k', "public ${value} put(final K k")
    $content = $content.Replace('public int putIfAbsent(final K k', "public ${value} putIfAbsent(final K k")
    $content = $content.Replace('public int addTo(final K k', "public ${value} addTo(final K k")
    $content = $content.Replace('public int replace(final K k, final int oldValue, final int v)', "public ${value} replace(final K k, final ${value} oldValue, final ${value} v)")
    $content = $content.Replace('public int replace(final K k, final int v)', "public ${value} replace(final K k, final ${value} v)")
    $content = $content.Replace('public boolean remove(final Object k, final int v)', "public boolean remove(final Object k, final ${value} v)")
    $content = $content.Replace('public int getOrDefault(final Object k, final int defaultValue)', "public ${value} getOrDefault(final Object k, final ${value} defaultValue)")
    $content = $content.Replace('public int remove' + $name + '(final Object k)', "public ${value} remove${name}(final Object k)")
    $content = $content.Replace('public int get' + $name + '(final Object k)', "public ${value} get${name}(final Object k)")
    $content = $content.Replace('public int compute' + $name + 'IfPresent(final K k', "public ${value} compute${name}IfPresent(final K k")
    $content = $content.Replace('public int compute' + $name + '(final K k', "public ${value} compute${name}(final K k")
    $content = $content.Replace('public int merge' + $name + '(final K k, final int v', "public ${value} merge${name}(final K k, final ${value} v")
    $content = $content.Replace('public int merge(final K k, final int v', "public ${value} merge(final K k, final ${value} v")
    $content = $content.Replace('public int computeIfAbsent(final K k, final Object2' + $name + 'Function', "public ${value} computeIfAbsent(final K k, final Object2${name}Function")
    $content = $content.Replace('public int get' + $name + 'Value()', "public ${value} get${name}Value()")
    $content = $content.Replace('public int right' + $name + '()', "public ${value} right${name}()")
    $content = $content.Replace('public int setValue(final int v)', "public ${value} setValue(final ${value} v)")
    $content = $content.Replace('public Object' + $name + 'Pair<K> right(final int v)', "public Object${name}Pair<K> right(final ${value} v)")
    $content = $content.Replace('public int next' + $name + '()', "public ${value} next${name}()")
    $content = $content.Replace('public boolean contains(int v)', "public boolean contains(${value} v)")
    # entry-set value extraction (must precede the generic 'final int v' rewrite)
    $content = $content.Replace('final int v = (Integer) (e.getValue());', "final ${value} v = ((${wrapper}) (e.getValue())).${prim}Value();")
    $content = $content.Replace('!(e.getValue() instanceof Integer)', "!(e.getValue() instanceof ${wrapper})")
    # remaining value-typed parameters and locals
    $content = $content.Replace('final int v', "final ${value} v")
    $content = $content.Replace('final int oldValue', "final ${value} oldValue")
    $content = $content.Replace('final int incr', "final ${value} incr")
    $content = $content.Replace('int newVal = newValue;', "${value} newVal = newValue;")
    $content = $content.Replace('final int newValue = mappingFunction.applyAsInt(k);', "final ${value} newValue = mappingFunction.get${name}(k);")
    $content = $content.Replace('final int newValue = remappingFunction.applyAsInt(value[pos], v);', "final ${value} newValue = remappingFunction.apply(value[pos], v);")
    # boxed values for BiFunction variants
    $content = $content.Replace('final Integer newValue = remappingFunction.apply((k), value[pos]);', "final ${wrapper} newValue = remappingFunction.apply((k), ${wrapper}.valueOf(value[pos]));")
    $content = $content.Replace('final Integer newValue = remappingFunction.apply((k), pos >= 0 ? value[pos] : null);', "final ${wrapper} newValue = remappingFunction.apply((k), pos >= 0 ? ${wrapper}.valueOf(value[pos]) : null);")
    $content = $content.Replace('final Integer newValue = remappingFunction.apply(value[pos], v);', "final ${wrapper} newValue = remappingFunction.apply(${wrapper}.valueOf(value[pos]), ${wrapper}.valueOf(v));")

    # ---- 5. MapEntry.equals ----
    if ($code -eq 'D') {
        $content = $content.Replace('((value[index]) == (e.getValue()))', '(Double.doubleToRawLongBits(value[index]) == Double.doubleToRawLongBits((e.getValue()).doubleValue()))')
    } elseif ($code -eq 'F') {
        $content = $content.Replace('((value[index]) == (e.getValue()))', '(Float.floatToRawIntBits(value[index]) == Float.floatToRawIntBits((e.getValue()).floatValue()))')
    } else {
        $content = $content.Replace('((value[index]) == (e.getValue()))', "((value[index]) == ((e.getValue()).${prim}Value()))")
    }

    # ---- 6. Narrowing casts for byte/short/char addTo ----
    if ($code -in @('B', 'S', 'C')) {
        $cast = '(' + $value + ')'
        $content = $content.Replace('value[pos] = oldValue + incr;', "value[pos] = ${cast}(oldValue + incr);")
        $content = $content.Replace('value[pos] = defRetValue + incr;', "value[pos] = ${cast}(defRetValue + incr);")
    }

    # ---- 7. Double/float: raw-bits comparisons, hash2int, zero literal, applyAsDouble ----
    if ($code -eq 'D') {
        $content = $content.Replace('((value[n]) == (v))', '(Double.doubleToRawLongBits(value[n]) == Double.doubleToRawLongBits(v))')
        $content = $content.Replace('((value[pos]) == (v))', '(Double.doubleToRawLongBits(value[pos]) == Double.doubleToRawLongBits(v))')
        $content = $content.Replace('t ^= value[i];', 't ^= it.unimi.dsi.fastutil.HashCommon.double2int(value[i]);')
        $content = $content.Replace('h += value[n];', 'h += it.unimi.dsi.fastutil.HashCommon.double2int(value[n]);')
        $content = $content.Replace('hash[index] ^ value[index];', 'hash[index] ^ it.unimi.dsi.fastutil.HashCommon.double2int(value[index]);')
        $content = $content.Replace('value[last] = 0;', 'value[last] = 0d;')
        $content = $content.Replace('final double newValue = remappingFunction.apply(value[pos], v);', 'final double newValue = remappingFunction.applyAsDouble(value[pos], v);')
    } elseif ($code -eq 'F') {
        $content = $content.Replace('((value[n]) == (v))', '(Float.floatToRawIntBits(value[n]) == Float.floatToRawIntBits(v))')
        $content = $content.Replace('((value[pos]) == (v))', '(Float.floatToRawIntBits(value[pos]) == Float.floatToRawIntBits(v))')
        $content = $content.Replace('t ^= value[i];', 't ^= it.unimi.dsi.fastutil.HashCommon.float2int(value[i]);')
        $content = $content.Replace('h += value[n];', 'h += it.unimi.dsi.fastutil.HashCommon.float2int(value[n]);')
        $content = $content.Replace('hash[index] ^ value[index];', 'hash[index] ^ it.unimi.dsi.fastutil.HashCommon.float2int(value[index]);')
        $content = $content.Replace('value[last] = 0;', 'value[last] = 0f;')
    } elseif ($code -eq 'Z') {
        $content = $content.Replace('t ^= value[i];', 't ^= (value[i] ? 1231 : 1237);')
        $content = $content.Replace('h += value[n];', 'h += (value[n] ? 1231 : 1237);')
        $content = $content.Replace('hash[index] ^ value[index];', 'hash[index] ^ (value[index] ? 1231 : 1237);')
        $content = $content.Replace('value[last] = 0;', 'value[last] = false;')
    }

    # ---- 8. Operator signature for non-boolean mergeXxx ----
    if ($code -ne 'Z') {
        $content = $content.Replace('java.util.function.IntBinaryOperator', $op)
    }

    # ---- 9. Deprecated boxed getValue/setValue ----
    $content = $content.Replace('public Integer getValue()', "public ${wrapper} getValue()")
    $content = $content.Replace('public Integer setValue(final Integer v)', "public ${wrapper} setValue(final ${wrapper} v)")
    $content = $content.Replace('return setValue((v).intValue());', "return ${wrapper}.valueOf(setValue((v).${prim}Value()));")

    # write back with the repository's CRLF line endings
    return $content -replace "`n", "`r`n"
}

foreach ($t in $types) {
    $normalName = "O2$($t.Code)OpenCacheHashMap"
    $customName = "O2$($t.Code)OpenCustomCacheHashMap"

    $n = Transform (Read-Template 'O2IOpenCacheHashMap') $t $false
    Write-File (Join-Path $srcDir "$normalName.java") $n

    $c = Transform (Read-Template 'O2IOpenCustomCacheHashMap') $t $true
    Write-File (Join-Path $srcDir "$customName.java") $c

    Write-Host "generated $normalName / $customName"
}
Write-Host 'done'
