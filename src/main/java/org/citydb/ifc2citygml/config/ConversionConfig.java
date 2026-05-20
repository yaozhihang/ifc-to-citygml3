package org.citydb.ifc2citygml.config;

public record ConversionConfig(
        boolean noReferences,
        boolean reorientShells,
        boolean noProperties,
        boolean georefOktoberfest,
        boolean listUnmappedDoorsWindows,
        boolean unrelatedDoorsWindowsInDummyBce,
        boolean noGenericAttributeSets,
        boolean setNamesAsPrefixes,
        boolean noStoreys,
        boolean noAppearances,
        double xOffset,
        double yOffset,
        double zOffset,
        String defaultSrsName
) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean noReferences;
        private boolean reorientShells;
        private boolean noProperties;
        private boolean georefOktoberfest;
        private boolean listUnmappedDoorsWindows;
        private boolean unrelatedDoorsWindowsInDummyBce;
        private boolean noGenericAttributeSets;
        private boolean setNamesAsPrefixes;
        private boolean noStoreys;
        private boolean noAppearances;
        private double xOffset;
        private double yOffset;
        private double zOffset;
        private String defaultSrsName;

        private Builder() {
        }

        public Builder noReferences(boolean v) {
            this.noReferences = v;
            return this;
        }

        public Builder reorientShells(boolean v) {
            this.reorientShells = v;
            return this;
        }

        public Builder noProperties(boolean v) {
            this.noProperties = v;
            return this;
        }

        public Builder georefOktoberfest(boolean v) {
            this.georefOktoberfest = v;
            return this;
        }

        public Builder listUnmappedDoorsWindows(boolean v) {
            this.listUnmappedDoorsWindows = v;
            return this;
        }

        public Builder unrelatedDoorsWindowsInDummyBce(boolean v) {
            this.unrelatedDoorsWindowsInDummyBce = v;
            return this;
        }

        public Builder noGenericAttributeSets(boolean v) {
            this.noGenericAttributeSets = v;
            return this;
        }

        public Builder setNamesAsPrefixes(boolean v) {
            this.setNamesAsPrefixes = v;
            return this;
        }

        public Builder noStoreys(boolean v) {
            this.noStoreys = v;
            return this;
        }

        public Builder noAppearances(boolean v) {
            this.noAppearances = v;
            return this;
        }

        public Builder xOffset(double v) {
            this.xOffset = v;
            return this;
        }

        public Builder yOffset(double v) {
            this.yOffset = v;
            return this;
        }

        public Builder zOffset(double v) {
            this.zOffset = v;
            return this;
        }

        public Builder defaultSrsName(String v) {
            this.defaultSrsName = v;
            return this;
        }

        public ConversionConfig build() {
            return new ConversionConfig(
                    noReferences, reorientShells, noProperties, georefOktoberfest,
                    listUnmappedDoorsWindows, unrelatedDoorsWindowsInDummyBce,
                    noGenericAttributeSets, setNamesAsPrefixes, noStoreys, noAppearances,
                    xOffset, yOffset, zOffset, defaultSrsName
            );
        }
    }
}
