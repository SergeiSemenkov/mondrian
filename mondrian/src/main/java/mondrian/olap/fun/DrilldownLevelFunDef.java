/*
 * This software is subject to the terms of the Eclipse Public License v1.0
 * Agreement, available at the following URL:
 * http://www.eclipse.org/legal/epl-v10.html.
 * You must accept the terms of that agreement to use this software.
 *
 * Copyright (c) 2002-2017 Hitachi Vantara.
 * Copyright (C) 2021 Topsoft
 * Copyright (c) 2021-2022 Sergei Semenkov
 * All rights reserved.
 */

package mondrian.olap.fun;

import mondrian.calc.*;
import mondrian.calc.impl.AbstractListCalc;
import mondrian.calc.impl.UnaryTupleList;
import mondrian.mdx.ResolvedFunCall;
import mondrian.olap.*;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;

/**
 * Definition of the <code>DrilldownLevel</code> MDX function.
 *
 * <p>Syntax:
 *
 * <blockquote><pre>
 * DrilldownLevel(Set_Expression[, Level_Expression])
 * DrilldownLevel(Set_Expression, , Numeric_Expression)
 * </pre></blockquote>
 *
 * @author jhyde
 * @since Mar 23, 2006
 */
class DrilldownLevelFunDef extends FunDefBase {
    public static String INCLUDE_CALC_MEMBERS = "INCLUDE_CALC_MEMBERS";

    static final ReflectiveMultiResolver Resolver =
            new ReflectiveMultiResolver(
                    "DrilldownLevel",
                    "DrilldownLevel(<Set>[, <Level>]) or DrilldownLevel(<Set>, , <Index>)",
                    "Drills down the members of a set, at a specified level, to one level below. Alternatively, drills down on a specified dimension in the set.",
                    new String[]{"fxx", "fxxl", "fxxen", "fxxly", "fxxeny", "fxxeey"},
                    DrilldownLevelFunDef.class,
                    new String[]{INCLUDE_CALC_MEMBERS});

    public DrilldownLevelFunDef(FunDef dummyFunDef) {
        super(dummyFunDef);
    }

    public Calc compileCall(ResolvedFunCall call, ExpCompiler compiler) {
        final ListCalc listCalc =
            compiler.compileList(call.getArg(0));
        final LevelCalc levelCalc =
            call.getArgCount() > 1
                && call.getArg(1).getType()
                instanceof mondrian.olap.type.LevelType
                ? compiler.compileLevel(call.getArg(1))
                : null;
        // DrilldownLevel(set, level, INCLUDE_CALC_MEMBERS): the flag as the third of three
        // arguments, which Excel sends for every drill-down of a user hierarchy. It is not the
        // tuple index of DrilldownLevel(set, , index): taken for one, the level was ignored and
        // every member was drilled again, repeating the members an inner DrilldownLevel had
        // drilled already (Excel showed USA twice). Regression: emondrian-modules
        // DrilldownLevelCalcMembersTest.
        final boolean flagThird =
            call.getArgCount() == 3
                && call.getArg(2) instanceof Literal
                && INCLUDE_CALC_MEMBERS.equals(((Literal)call.getArg(2)).getValue());
        final IntegerCalc indexCalc =
            call.getArgCount() > 2
                && !flagThird
                && call.getArg(2) != null
                && !(call.getArg(2).getType() instanceof mondrian.olap.type.EmptyType)
                ? compiler.compileInteger(call.getArg(2))
                : null;
        final int arity = listCalc.getType().getArity();
        final boolean includeCalcMembers =
                flagThird
                || (call.getArgCount() == 4
                    && call.getArg(3) != null
                    && call.getArg(3) instanceof Literal
                    && INCLUDE_CALC_MEMBERS.equals(((Literal)call.getArg(3)).getValue())
                )
                ;
        if (indexCalc == null) {
            return new AbstractListCalc(call, new Calc[] {listCalc, levelCalc})
            {
                public TupleList evaluateList(Evaluator evaluator) {
                    TupleList list = listCalc.evaluateList(evaluator);
                    if (list.size() == 0) {
                        return list;
                    }
                    int searchDepth = -1;
                    if (levelCalc != null) {
                        Level level = levelCalc.evaluateLevel(evaluator);
                        searchDepth = level.getDepth();
                    }
                    return new UnaryTupleList(
                        drill(searchDepth, list.slice(0), evaluator, includeCalcMembers));
                }
            };
        } else {
            return new AbstractListCalc(call, new Calc[] {listCalc, indexCalc})
            {
                public TupleList evaluateList(Evaluator evaluator) {
                    TupleList list = listCalc.evaluateList(evaluator);
                    if (list.isEmpty()) {
                        return list;
                    }
                    final int index = indexCalc.evaluateInteger(evaluator);
                    if (index < 0 || index >= arity) {
                        return list;
                    }
                    HashMap<Member,List<Member>> calcMembersByParent = getCalcMembersByParent(
                            list.get(0).get(index).getHierarchy(),
                            evaluator,
                            includeCalcMembers);
                    TupleList result = TupleCollections.createList(arity);
                    final SchemaReader schemaReader =
                            evaluator.getSchemaReader();
                    final Member[] tupleClone = new Member[arity];
                    for (List<Member> tuple : list) {
                        result.add(tuple);
                        final List<Member> children =
                                schemaReader.getMemberChildren(tuple.get(index));
                        for (Member child : children) {
                            tuple.toArray(tupleClone);
                            tupleClone[index] = child;
                            result.addTuple(tupleClone);
                        }
                        List<Member> childrenCalcMembers = calcMembersByParent.get(tuple.get(index));
                        if(childrenCalcMembers != null) {
                            for (Member childMember : childrenCalcMembers) {
                                tuple.toArray(tupleClone);
                                tupleClone[index] = childMember;
                                result.addTuple(tupleClone);
                            }
                        }
                    }
                    return result;
                }
            };
        }
    }

    HashMap<Member,List<Member>> getCalcMembersByParent(Hierarchy hierarchy, Evaluator evaluator, boolean includeCalcMembers) {
        List<Member> calculatedMembers;
        if(includeCalcMembers) {
            final SchemaReader schemaReader =
                    evaluator.getSchemaReader();
            calculatedMembers = schemaReader.getCalculatedMembers(hierarchy);
        }
        else {
            calculatedMembers = new ArrayList<Member>();
        }
        HashMap<Member,List<Member>> calcMembersByParent = new HashMap<Member,List<Member>>();
        for(Member member: calculatedMembers) {
            if(member.getParentMember() != null) {
                List<Member> children = calcMembersByParent.get(member.getParentMember());
                if(children == null) {
                    children = new ArrayList<Member>();
                    calcMembersByParent.put(member.getParentMember(), children);
                }
                children.add(member);
            }
        }
        return calcMembersByParent;
    }

    List<Member> drill(int searchDepth, List<Member> list, Evaluator evaluator, boolean includeCalcMembers)
    {
        HashMap<Member,List<Member>> calcMembersByParent = getCalcMembersByParent(
                list.get(0).getHierarchy(),
                evaluator,
                includeCalcMembers);

        if (searchDepth == -1) {
            searchDepth = list.get(0).getLevel().getDepth();

            for (int i = 1, m = list.size(); i < m; i++) {
                Member member = list.get(i);
                int memberDepth = member.getLevel().getDepth();

                if (memberDepth > searchDepth) {
                    searchDepth = memberDepth;
                }
            }
        }

        List<Member> drilledSet = new ArrayList<Member>();

        List<Member> parentMembers = new ArrayList<Member>();

        for (int i = 0, m = list.size(); i < m; i++) {
            Member member = list.get(i);
            drilledSet.add(member);

            Member nextMember =
                i == (m - 1)
                ? null
                : list.get(i + 1);

            //
            // This member is drilled if it's at the correct depth
            // and if it isn't drilled yet. A member is considered
            // to be "drilled" if it is immediately followed by
            // at least one descendant
            //
            if (member.getLevel().getDepth() == searchDepth
                && !FunUtil.isAncestorOf(member, nextMember, true))
            {
                parentMembers.add(member);
            }
        }

        for(Member parentMember: parentMembers) {
            List<Member> childMembers =
                    evaluator.getSchemaReader().getMemberChildren(parentMember);
            for (Member childMember : childMembers) {
                drilledSet.add(childMember);
            }
            List<Member> childrenCalcMembers = calcMembersByParent.get(parentMember);
            if(childrenCalcMembers != null) {
                for (Member childMember : childrenCalcMembers) {
                    drilledSet.add(childMember);
                }
            }
        }

        return drilledSet;
    }
}

// End DrilldownLevelFunDef.java
