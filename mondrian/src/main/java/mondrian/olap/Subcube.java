// Copyright (c) 2021-2024 Sergei Semenkov
// All rights reserved.

package mondrian.olap;

import java.io.PrintWriter;
import java.util.*;

public class Subcube extends QueryPart {
    private final String cubeName;
    private final Subcube subcube;
    private final QueryAxis[] axes;
    private final QueryAxis slicerAxis;

    public Subcube(
            String cubeName,
            Subcube subcube,
            QueryAxis[] axes,
            QueryAxis slicerAxis)
    {
        this.cubeName = cubeName;
        this.subcube = subcube;
        this.axes = axes;
        this.slicerAxis = slicerAxis;
    }

    @Override
    public void unparse(PrintWriter pw) {
        unparse(pw, this.cubeName);
//        pw.print("CREATE SESSION MEMBER ");
//        pw.print("[" + cubeName + "]");
//        pw.print(".");
//        if(e != null) {
//            pw.print(e.getIdentifier());
//            pw.print(" AS ");
//            //pw.print(e,getExpression());
//        }
//        pw.println();
    }

    public void unparse(PrintWriter pw, String cubeName) {
        if(this.subcube != null) {
            pw.println("(");
            pw.println("select ");
            for (int i = 0; i < axes.length; i++) {
                pw.print("  ");
                axes[i].unparse(pw);
                if (i < axes.length - 1) {
                    pw.println(",");
                    pw.print("  ");
                } else {
                    pw.println();
                }
            }
            pw.println("from ");
            if (subcube != null) {
                subcube.unparse(pw);
            }
            if (slicerAxis != null) {
                pw.print("where ");
                slicerAxis.unparse(pw);
                pw.println();
            }
            pw.println(")");
        }
        else {
            pw.println("[" + cubeName + "]");
        }
    }


    @Override
    public Object[] getChildren() {
        return new Object[] {cubeName};
    }

    public String getCubeName() {
        if(this.subcube != null) {
            return this.subcube.getCubeName();
        }
        else {
            return this.cubeName;
        }
    }

    public List<Exp> getAxisExps() {
        ArrayList<Exp> exps = new ArrayList<Exp>();
        if(this.subcube != null) {
            exps.addAll(this.subcube.getAxisExps());
        }
        for(int i =0; i < this.axes.length; i++) {
            exps.add(this.axes[i].getSet());
        }
        if(this.slicerAxis != null) {
            exps.add(this.slicerAxis.getSet());
        }
        return exps;
    }

    public Subcube getSubcube() { return this.subcube; }

    public QueryAxis[] getAxes() { return this.axes; }

    public QueryAxis getSlicerAxis() { return this.slicerAxis; }
}

